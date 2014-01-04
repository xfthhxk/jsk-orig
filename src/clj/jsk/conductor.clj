(ns jsk.conductor
  "Coordination of workflows."
  (:require
            [jsk.quartz :as q]
            [clojurewerkz.quartzite.conversion :as qc]
            [jsk.workflow :as w]
            [jsk.messaging :as msg]
            [jsk.notification :as n]
            [jsk.db :as db]
            [jsk.ds :as ds]
            [jsk.conf :as conf]
            [jsk.util :as util]
            [jsk.graph :as g]
            [jsk.job :as j]
            [jsk.ps :as ps]
            [jsk.tracker :as track]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojure.core.async :refer [chan go go-loop put! >! <! thread]]
            [taoensso.timbre :as log])
  (:import (org.quartz JobDataMap JobDetail JobExecutionContext JobKey Scheduler)))

(declare start-workflow-execution)


(defn notify-error [{:keys [job-name execution-id error]}]
  (if error
    (let [to (conf/error-email-to)
          subject (str "[JSK ERROR] " job-name)
          body (str "Job execution ID: " execution-id "\n\n" error)]
      (log/info "Sending error email for execution: " execution-id)
      (n/mail to subject body))))


;-----------------------------------------------------------------------
; exec-tbl tracks executions and the status of each job in the workflow.
; It is also used to determine what job(s) to trigger next.
; {execution-id :info IExecutionInfo
;               :running-jobs-count 1
;               :failed? false}
;
;-----------------------------------------------------------------------
(def ^:private exec-infos (atom {}))

(def ^:private quartz-chan (chan))

(def ^:private info-chan (atom (chan)))

(def ^:private pub-sock (atom nil))
(def ^:private sub-sock (atom nil))

(def ^:private tracker (atom (track/new-tracker)))

;-----------------------------------------------------------------------
; Execution Info interactions
;-----------------------------------------------------------------------
(defn- add-exec-info!
  "Adds the execution-id and table to the exec-info. Also
   initailizes the running jobs count property."
  [exec-id info root-wf-name start-ts]
  (let [exec-wf-counts (zipmap (ds/workflows info) (repeat 0))]
    (swap! exec-infos assoc exec-id {:info info
                                     :root-wf-name root-wf-name
                                     :start-ts start-ts
                                     :running-jobs exec-wf-counts
                                     :failed-exec-wfs #{}})
    ; allows for aborting jobs/executions etc
    ; FIXME: this thing uses an atom also (need to store all this stuff in one place)
    (ps/begin-execution-tracking exec-id)))

(defn- rm-exec-info!
  "Removes the execution-id and all its data from the in memory store"
  [exec-id]
  (swap! exec-infos dissoc exec-id))

(defn get-exec-info
  "Look up the IExecutionInfo for the exec-id."
  [exec-id]
  (get-in @exec-infos [exec-id :info]))

(defn get-by-exec-id [id] (get @exec-infos id))

(defn execution-exists?
  "Answers true if the execution is known to the conductor."
  [exec-id]
  (-> exec-id get-by-exec-id nil? not))

(defn get-execution-root-wf-name [exec-id]
  (get-in @exec-infos [exec-id :root-wf-name]))

(defn get-execution-start-ts [exec-id]
  (get-in @exec-infos [exec-id :start-ts]))

(defn- update-running-jobs-count!
  "Updates the running jobs count based on the value of :execution-id.
  'f' is a fn such as inc/dec for updating the count.  Answers with the
  new running job count for the exec-wf-id in execution-id."
  [execution-id exec-wf-id f]
  (let [path [execution-id :running-jobs exec-wf-id]]
    (-> (swap! exec-infos update-in path f)
        (get-in path))))

(defn- running-jobs-count
  [execution-id exec-wf-id]
  (get-in @exec-infos [execution-id :running-jobs exec-wf-id]))

(defn- mark-exec-wf-failed!
  "Marks the exec-wf-id in exec-id as failed."
  [exec-id exec-wf-id]
  (swap! exec-infos update-in [exec-id :failed-exec-wfs] conj exec-wf-id))

(defn- exec-wf-failed?
  "Answers if the exec-wf failed"
  [exec-id exec-wf-id]
  (let [fails (get-in @exec-infos [exec-id :failed-exec-wfs])]
    (if (fails exec-wf-id)
      true
      false)))

(def ^:private exec-wf-success? (complement exec-wf-failed?))

(defn- pick-agent-for-job
  "Right now just picks an agent at random."
  [job-id]
  ; FIXME: this needs to return the agent based on job sets the agent can handle
  ; and also the least busy at the moment
  (-> @tracker track/agents vec rand-nth))

(defn- send-job-to-agent [{:keys[node-id execution-id exec-vertex-id timeout exec-wf-id]}]
  (let [agent-id (pick-agent-for-job node-id) ; nb node-id is the job id from the job table
        job (j/get-job node-id)               ; FIXME: get from cache in the future
        data {:msg :run-job
              :job job
              :execution-id execution-id
              :exec-vertex-id exec-vertex-id
              :exec-wf-id exec-wf-id
              :timeout timeout}]

    (log/info "Sending job: " job "to agent" agent-id " for execution-id:" execution-id ", vertex-id:" exec-vertex-id)

    (swap! tracker #(track/run-job %1 agent-id exec-vertex-id (util/now)))
    (msg/publish @pub-sock agent-id data)))


(defn- run-jobs
  "Fires off each exec vertex id in vertices.  exec-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files
   to.  exec-vertex-id is used by the execution.clj ns."
  [vertices exec-wf-id exec-id]
  (let [info (get-exec-info exec-id)]
    (doseq [v vertices
            :let [{:keys[node-id node-nm]} (ds/vertex-attrs info v)
                  data {:execution-id exec-id
                        :exec-wf-id exec-wf-id
                        :node-id node-id
                        :node-nm node-nm
                        :trigger-src :conductor
                        :timeout Integer/MAX_VALUE
                        :start-ts (db/now)
                        :exec-vertex-id v}]]
      ; publish msg so an agent will handle job execution
      (send-job-to-agent data))))


;-----------------------------------------------------------------------
; Runs all workflows with the execution-id specified.
;-----------------------------------------------------------------------
(defn- run-workflows [wfs exec-id]
  (let [info (get-exec-info exec-id)]
    (doseq [exec-vertex-id wfs
            :let [exec-wf-id (:exec-wf-to-run (ds/vertex-attrs info exec-vertex-id))]]
      (start-workflow-execution exec-vertex-id exec-wf-id exec-id))))

;-----------------------------------------------------------------------
; Runs all nodes handling jobs and workflows slightly differently.
;-----------------------------------------------------------------------
(defn- run-nodes
  "Fires off each node in node-ids.  execution-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files to."
  [node-ids exec-id]

  ; group-by node-ids by node-type
  (let [info (get-exec-info exec-id)
        [wf-id & others] (-> (ds/workflow-context info node-ids) vals distinct)
        f (fn[id](->> id (ds/vertex-attrs info) :node-type))
        type-map (group-by f node-ids)]

    (comment
      (log/debug "run-nodes: node-ids:" node-ids)
      (log/debug "run-nodes: info:" info)
      (log/debug "run-nodes: type-map:" type-map))

    (assert (nil? others)
            (str "wf-id: " wf-id ", others: " others
                 ". Expected nodes to belong to only one wf."))

    ; FIXME: these could be nil if group by doesn't produce
    ; a value from type-map

    (-> db/job-type-id type-map (run-jobs wf-id exec-id))
    (-> db/workflow-type-id type-map (run-workflows exec-id))))

;-----------------------------------------------------------------------
; Start a workflow from scratch or within the execution.
;-----------------------------------------------------------------------
(defn- start-workflow-execution
  ([wf-id]
   (try
     (let [wf-name (w/get-workflow-name wf-id)
           start-ts (db/now)
           {:keys[execution-id info]} (w/setup-execution wf-id)]

       (add-exec-info! execution-id info wf-name start-ts)
       (put! @info-chan {:event :execution-started
                         :execution-id execution-id
                         :start-ts start-ts
                         :wf-name wf-name})

       ; pass in nil for exec-vertex-id since the actual workflow is represented
       ; by the execution and is not a vertex in itself
       (start-workflow-execution nil (ds/root-workflow info) execution-id))
     (catch Exception e
       (log/error e))))

  ([exec-vertex-id exec-wf-id exec-id]
   (let [info (get-exec-info exec-id)
         roots (-> info (ds/workflow-graph exec-wf-id) g/roots)
         ts (db/now)]

     (db/workflow-started exec-wf-id ts)

     (if exec-vertex-id
       (db/execution-vertex-started exec-vertex-id ts))

     (put! @info-chan {:event :wf-started
                       :exec-vertex-id exec-vertex-id
                       :exec-wf-id exec-wf-id
                       :start-ts ts
                       :execution-id exec-id})
     (run-nodes roots exec-id))))

;-----------------------------------------------------------------------
; Find which things should execute next
;-----------------------------------------------------------------------
(defn- successor-nodes
  "Answers with the successor nodes for the execution status of node-id
  belonging to execution-id."
  [execution-id exec-vertex-id success?]
  (-> execution-id
      get-exec-info
      (ds/dependencies exec-vertex-id success?)))

;-----------------------------------------------------------------------
; Creates a synthetic workflow to run the specified job in.
;-----------------------------------------------------------------------
(defn- run-job-as-synthetic-wf
  "Runs the job in the context of a synthetic workflow."
  [job-id]
  (let [{:keys[execution-id info]} (w/setup-synthetic-execution job-id)
        start-ts (db/now)
        job-nm (->> info ds/vertices first (ds/vertex-attrs info) :node-nm)]

    (log/info "info: " info ", job-nm: " job-nm)
    (db/workflow-started (ds/root-workflow info) start-ts)
    (add-exec-info! execution-id info job-nm start-ts)
    (run-nodes (ds/vertices (get-exec-info execution-id))
               execution-id)))


;-----------------------------------------------------------------------
; Schedule job to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-job-now [job-id]
  (log/info "job-id is " job-id)
  (run-job-as-synthetic-wf job-id)
  true)

;-----------------------------------------------------------------------
; Schedule workflow to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-workflow-now
  [wf-id]
  (log/info "Triggering workflow with id: " wf-id)
  (start-workflow-execution wf-id)
  true)


(defn- abort-execution* [exec-id]
  (log/info "Aborting execution with id:" exec-id)
  (let [root-wf-id (-> exec-id get-exec-info ds/root-workflow)
        exec-name (get-execution-root-wf-name exec-id)
        start-ts (get-execution-start-ts exec-id)
        ts (db/now)]

    (ps/kill! exec-id)
    (ps/end-execution-tracking exec-id)

    (db/execution-aborted exec-id ts)

    (put! @info-chan {:event :execution-finished
                      :execution-id exec-id
                      :success? false
                      :status db/aborted-status
                      :finish-ts ts
                      :start-ts start-ts
                      :wf-name exec-name})

    (rm-exec-info! exec-id))
  (log/info "all done cleaning up execution: " exec-id)
  true)


;-----------------------------------------------------------------------
; Aborts the entire execution
;-----------------------------------------------------------------------
(defn abort-execution [exec-id]
  (if (execution-exists? exec-id)
    (abort-execution* exec-id)
    false))


(defn abort-execution-vertex [exec-vertex-id]
  )

;-----------------------------------------------------------------------
; Resume execution
;-----------------------------------------------------------------------
(defn- resume-execution* [exec-id exec-vertex-id]
  (let [wf-name (db/get-execution-name exec-id)
        start-ts (db/now)
        {:keys[info]} (w/resume-workflow-execution-data exec-id)]
    (add-exec-info! exec-id info wf-name start-ts)
    (db/update-execution-status exec-id db/started-status start-ts)
    (put! @info-chan {:event :execution-started
                      :execution-id exec-id
                      :start-ts start-ts
                      :wf-name wf-name})
    (run-nodes [exec-vertex-id] exec-id)))

(defn resume-execution [exec-id exec-vertex-id]
  (if (execution-exists? exec-id)
    {:success? false :error "Execution is already in progress."}
    (do
      (resume-execution* exec-id exec-vertex-id)
      {:success? true})))


(defn- execution-finished [exec-id success? last-exec-wf-id]
  (let [root-wf-id (-> exec-id get-exec-info ds/root-workflow)
        exec-name (get-execution-root-wf-name exec-id)
        start-ts (get-execution-start-ts exec-id)
        ts (db/now)]
    (db/workflow-finished root-wf-id success? ts)
    ; root wf doesn't have a vertex (it's the execution)
    ;(put! @info-chan {:event :wf-finished
    ;                  :execution-id exec-id
    ;                  :execution-vertices [root-wf-id]
    ;                  :success? success?})

    (db/execution-finished exec-id success? ts)
    (ps/end-execution-tracking exec-id)
    (put! @info-chan {:event :execution-finished
                      :execution-id exec-id
                      :success? success?
                      :status (if success? db/finished-success db/finished-error)
                      :finish-ts ts
                      :start-ts start-ts
                      :wf-name exec-name})

    (rm-exec-info! exec-id)))

(defn- parents-to-upd [vertex-id execution-id success?]
  (loop [v-id vertex-id ans {}]
    (let [{:keys[parent-vertex on-success on-failure belongs-to-wf]}
          (ds/vertex-attrs (get-exec-info execution-id) v-id)
          deps (if success? on-success on-failure)
          running-count (running-jobs-count execution-id belongs-to-wf)]
      (if (and parent-vertex (empty? deps) (zero? running-count))
        (recur parent-vertex (assoc ans parent-vertex belongs-to-wf))
        ans))))

(defn- mark-wf-and-parent-wfs-finished
  "Finds all parent workflows which also need to be marked as completed.
  NB exec-vertex-id can be nil if the workflow that finished is the root wf"
  [exec-vertex-id exec-wf-id execution-id success?]
  (let [ts (db/now)
        vertices-wf-map (parents-to-upd exec-vertex-id execution-id success?)
        vertices (if exec-vertex-id
                   (conj (keys vertices-wf-map) exec-vertex-id)
                   [])
        wfs (conj (vals vertices-wf-map) exec-wf-id)]
    (log/info "Marking finished for execution-id:" execution-id ", Vertices:" vertices ", wfs:" wfs)
    (db/workflows-and-vertices-finished vertices wfs success? ts)

    ; FIXME: need to iterate over all the vertices and put on info-chan
    (if vertices
      (put! @info-chan {:event :wf-finished
                        :execution-id execution-id
                        :execution-vertices vertices
                        :finish-ts ts
                        :success? success?}))))


(defn- when-wf-finished [execution-id exec-wf-id exec-vertex-id]
  (let [wf-success? (exec-wf-success? execution-id exec-wf-id)
        next-nodes (successor-nodes execution-id exec-vertex-id wf-success?)
        exec-failed? (and (not wf-success?) (empty? next-nodes))
        exec-success? (and wf-success? (empty? next-nodes))]

    (mark-wf-and-parent-wfs-finished exec-vertex-id exec-wf-id execution-id wf-success?)

    (run-nodes next-nodes execution-id)

    ; execution finished?
    (if (or exec-failed? exec-success?)
      (execution-finished execution-id exec-success? exec-wf-id))))

(defn- when-job-started
  "Logs the status to the db.  Increments the running job count for the execution id."
  [{:keys[execution-id exec-wf-id exec-vertex-id agent-id] :as data}]
  (db/execution-vertex-started exec-vertex-id (db/now))

  ; FIXME: 2 atoms being updated individually
  (update-running-jobs-count! execution-id exec-wf-id inc)
  (swap! tracker #(track/agent-started-job %1 agent-id exec-vertex-id (util/now)))

  ; FIXME: this doesn't have all the info like the job name, start-ts see old execution.clj
  (put! @info-chan data))


; FIXME: update-running-jobs-count! is updating an atom and so is the code below put in dosync or what not
; Make idempotent, so if agent sends this again we don't blow up.
(defn- when-job-finished
  "Decrements the running job count for the execution id.
   Determines next set of jobs to run.
   Determines if the workflow is finished and/or errored.
   Sends job-finished-ack to agent.
   Publishes status on info-chan for distribution."
  [{:keys[execution-id exec-vertex-id agent-id status exec-wf-id success?] :as msg}]
  (log/debug "job-finished: " msg)

  ; update status in db
  (db/execution-vertex-finished exec-vertex-id (if success? db/finished-success db/finished-error) (db/now))

  (let [new-count (update-running-jobs-count! execution-id exec-wf-id dec)
        next-nodes (successor-nodes execution-id exec-vertex-id success?)
        exec-wf-fail? (and (not success?) (empty? next-nodes))
        exec-wf-finished? (and (zero? new-count) (empty? next-nodes))
        {:keys[parent-vertex]} (-> (get-exec-info execution-id)
                                   (ds/vertex-attrs exec-vertex-id))]

    ; update status memory and ack the agent so it can clear it's memory
    (swap! tracker #(track/rm-job %1 agent-id exec-vertex-id))
    (msg/publish @pub-sock agent-id {:msg :job-finished-ack :execution-id execution-id :exec-vertex-id exec-vertex-id})

    (if exec-wf-fail?
      (mark-exec-wf-failed! execution-id exec-wf-id))

    (if exec-wf-finished?
      (when-wf-finished execution-id exec-wf-id exec-vertex-id)
      (run-nodes next-nodes execution-id))))

;-----------------------------------------------------------------------
; -- Networked agents --
;-----------------------------------------------------------------------


(defn destroy []
  (q/stop))

;-----------------------------------------------------------------------
; Read jobs from database and creates them in quartz.
;-----------------------------------------------------------------------
(defn- populate-quartz-jobs []
  (let [jj (j/ls-jobs)]
    (log/info "Setting up " (count jj) " jobs in Quartz.")
    (doseq [j jj]
      (q/save-job! j))))

;-----------------------------------------------------------------------
; Read schedules from database and associates them to jobs in quartz.
;-----------------------------------------------------------------------
(defn- populate-quartz-triggers []
  (doseq [{:keys[cron-expression node-id node-schedule-id node-type-id]}
          (db/enabled-nodes-schedule-info)]
    (q/schedule-cron-job! node-schedule-id node-id node-type-id cron-expression)))

(defn- populate-quartz []
  (populate-quartz-jobs)
  (populate-quartz-triggers))


(defn- zero-agents? []
  (-> @tracker track/agents count zero?))


(defmulti dispatch :msg)


(defmethod dispatch :agent-registering [{:keys[agent-id]}]
  (log/info "Agent registering: " agent-id)
  (swap! tracker #(track/add-agent %1 agent-id (util/now))))

(defmethod dispatch :heartbeat-ack [{:keys[agent-id]}]
  (swap! tracker #(track/agent-heartbeat-rcvd %1 agent-id (util/now))))

(defmethod dispatch :run-job-ack [data]
  (log/info "Run job ack:" data)
  (when-job-started data))

(defmethod dispatch :job-finished [data]
  (when-job-finished data))


(defn- run-agent-request-processing [sock]
  (loop [data (msg/read-pub-data sock)]
    (try
      (dispatch data)
      (catch Exception ex
         (log/error ex)))
    (recur (msg/read-pub-data sock))))

(defn- broadcast-agent-registration [sock t]
  (while true
    (msg/publish sock "broadcast" {:msg :agents-register})
    (Thread/sleep t)))

(defn- broadcast-agent-registration-orig [sock]
  (msg/publish sock "broadcast" {:msg :agents-register}))

(defn- send-noops [sock n]
  (dotimes [i n]
    (msg/publish sock "broadcast" {:msg :noop :id i})))


(defn- run-heartbeats [sock t]
  (let [hb-id (atom 1)]
    (while true
      (msg/publish sock "broadcast" {:msg :heartbeat})
      ;(msg/publish sock "broadcast" {:msg :heartbeat :hb-id @hb-id})
      ;(swap! hb-id inc)
      (Thread/sleep t))))


(defn init [pub-port sub-port]
  (log/info "Connecting to database.")
  (conf/init-db)
  (let [p-sock (msg/make-socket "tcp" "*" pub-port true :pub)
        s-sock (msg/make-socket "tcp" "*" sub-port true :sub)]

    (reset! pub-sock p-sock)
    (reset! sub-sock s-sock)


    ; handle agent request messages in another thread
    (msg/subscribe-everything @sub-sock)

    ; FIXME:
    ; start heartbeats -- for some reason if heartbeats are started first
    ; then everything else works ok
    ; switching the order
    (future (run-heartbeats @pub-sock 1000))
    (future (run-agent-request-processing @sub-sock))

    ;FIXME: make sure at least 1 agent is connected
    ; Do this with a watch or something? this seems lame
    (while (zero-agents?)
      (log/info "No agents connected. Waiting..")
      (broadcast-agent-registration-orig @pub-sock)
      (Thread/sleep 1000))


    (q/init quartz-chan)
    (log/info "Quartz initialized.")

    (populate-quartz)
    (log/info "Quartz populated.")

    (q/start)
    (log/info "Quartz started.")

    ; quartz puts stuff on the quartz-chan when a trigger is fired
    (go-loop [{:keys[event node-id]} (<! quartz-chan)]
       (try
         (case event
           :trigger-wf (start-workflow-execution node-id)
           :trigger-job (run-job-as-synthetic-wf node-id))
         (catch Exception ex
           (log/error ex)))
       (recur (<! quartz-chan)))

    (go-loop [info (<! @info-chan)]
      (log/info "::INFO::" info)
      (recur (<! @info-chan)))))













