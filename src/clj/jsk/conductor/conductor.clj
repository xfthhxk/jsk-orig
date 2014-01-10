(ns jsk.conductor.conductor
  "Coordination of workflows."
  (:require
            [jsk.conductor.quartz :as quartz]
            [jsk.conductor.execution-model :as exm]
            [jsk.conductor.execution-setup :as exs]
            [jsk.common.workflow :as w]
            [jsk.common.messaging :as msg]
            [jsk.common.notification :as notify]
            [jsk.common.db :as db]
            [jsk.common.data :as data]
            [jsk.common.conf :as conf]
            [jsk.common.util :as util]
            [jsk.common.graph :as g]
            [jsk.common.job :as j]
            [jsk.conductor.agent-tracker :as track]
            [jsk.conductor.cache :as cache]
            [clojure.string :as string]
            [clojure.core.async :refer [chan go-loop put! <!]]
            [taoensso.timbre :as log]))

(declare start-workflow-execution when-job-finished)


;-----------------------------------------------------------------------
; exec-tbl tracks executions and the status of each job in the workflow.
; It is also used to determine what job(s) to trigger next.
; {execution-id :info IExecutionInfo
;               :running-jobs-count 1
;               :failed? false}
;
;-----------------------------------------------------------------------
; todo: turn in to defonce
; wrap this stuff in delay, they are executed when the file is required!
(def ^:private exec-infos (atom {}))

(def ^:private quartz-chan (chan))

(def ^:private publish-chan (chan))

; do this in the init
(def ^:private tracker (atom (track/new-tracker)))
(def ^:private node-sched-cache (atom nil))

(defn- publish [topic data]
  (put! publish-chan {:topic topic :data data}))

(defn- publish-event [data]
  (publish msg/status-updates-topic data))

;-----------------------------------------------------------------------
; Execution Info interactions
;-----------------------------------------------------------------------
(defn- add-exec-info!
  "Adds the execution-id and table to the exec-info. Also
   initailizes the running jobs count property."
  [exec-id info root-wf-name start-ts]
  (let [exec-wf-counts (zipmap (exm/workflows info) (repeat 0))]
    (swap! exec-infos assoc exec-id {:info info
                                     :root-wf-name root-wf-name
                                     :start-ts start-ts
                                     :running-jobs exec-wf-counts
                                     :failed-exec-wfs #{}})))

(defn- all-execution-ids []
  (-> @exec-infos keys))

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

  ; This logic moved to the ds namespace
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


(comment
  ; commented out this isn't used yet anyway
  (defn- matched-vertices-for-exec-id
    "Returns a subset of vertex-ids which belong to exec-id"
    [exec-id vertex-ids]
    (if-let[tbl (get-exec-info exec-id)]
      (clojure.set/intersection (exm/vertices tbl) vertex-ids)))

  (defn- vertices-by-execution-ids
    "Returns a map of execution-ids to set of vertex-ids"
    [vertex-ids]
    (loop [ex-ids (all-execution-ids) v-ids (set vertex-ids) ans {}]

      (if (or (empty? v-ids) (empty? ex-ids))
        ans
        (let [[e-id & rest-ex-ids] ex-ids
              matched (matched-vertices-for-exec-id e-id v-ids)]
          (if matched
            (recur rest-ex-ids (clojure.set/difference v-ids matched) (assoc ans e-id matched))
            (recur rest-ex-ids v-ids ans))))))
  )

(defn- mark-exec-wf-failed!
  "Marks the exec-wf-id in exec-id as failed."
  [exec-id exec-wf-id]
  (swap! exec-infos update-in [exec-id :failed-exec-wfs] conj exec-wf-id))

(defn- exec-wf-failed?
  "Answers if the exec-wf failed"
  [exec-id exec-wf-id]
  ; may not need this whole let thing, just call getin potentially
  (let [fails (get-in @exec-infos [exec-id :failed-exec-wfs])]
    (if (fails exec-wf-id)
      true
      false)))

(def ^:private exec-wf-success? (complement exec-wf-failed?))

;-----------------------------------------------------------------------
; TODO: Pick agents by the jobs they can handle and which is least busy
;-----------------------------------------------------------------------
(defn- pick-agent-for-job
  "Right now just picks an agent at random. Returns nil if no agent available for job."
  [job-id]
  (let [agents (track/agents @tracker)]
    (when (seq agents) (rand-nth (vec agents)))

    #_(if (empty? agents)
      nil
      (rand-nth (vec agents)))))




;-----------------------------------------------------------------------
; Sends the :run-job request to the agent. If no agent is available to
; handle the job forces failure of this exec-vertex-id so processing
; can move to the next dependency if any..
;-----------------------------------------------------------------------
; use select-keys and merge/assoc
(defn- send-job-to-agent [{:keys[node-id execution-id exec-vertex-id timeout exec-wf-id]} agent-id]
  (let [job (j/get-job node-id)               ; TODO: get from cache in the future
        data {:msg :run-job
              :job job
              :execution-id execution-id
              :exec-vertex-id exec-vertex-id
              :exec-wf-id exec-wf-id
              :timeout timeout}]


    ; fixme: use log/infof
    (log/info "Sending job: " job "to agent" agent-id " for execution-id:" execution-id ", vertex-id:" exec-vertex-id)
    (swap! tracker #(track/run-job %1 agent-id exec-vertex-id (util/now)))
    (publish agent-id data)))


(defn- run-jobs
  "Fires off each exec vertex id in vertices.  exec-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files to."
  [vertices exec-wf-id exec-id]
  (let [info (get-exec-info exec-id)]
    (doseq [v vertices
            :let [{:keys[node-id node-nm]} (exm/vertex-attrs info v)
                  agent-id (pick-agent-for-job node-id)
                  data {:execution-id exec-id
                        :exec-wf-id exec-wf-id
                        :node-id node-id
                        :node-nm node-nm
                        :trigger-src :conductor
                        :timeout Integer/MAX_VALUE
                        :start-ts (util/now)
                        :exec-vertex-id v}]]
      (if agent-id
        (send-job-to-agent data agent-id)
        (when-job-finished {:forced-by-conductor? true
                            :success? false
                            :execution-id exec-id
                            :exec-vertex-id v
                            :exec-wf-id exec-wf-id})))))



;-----------------------------------------------------------------------
; Runs all workflows with the execution-id specified.
;-----------------------------------------------------------------------
(defn- run-workflows [wfs exec-id]
  (let [info (get-exec-info exec-id)]
    (doseq [exec-vertex-id wfs
            :let [exec-wf-id (:exec-wf-to-run (exm/vertex-attrs info exec-vertex-id))]]
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
        [wf-id & others] (-> (exm/workflow-context info node-ids) vals distinct)
        f (fn[id](->> id (exm/vertex-attrs info) :node-type))
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

    (-> data/job-type-id type-map (run-jobs wf-id exec-id))
    (-> data/workflow-type-id type-map (run-workflows exec-id))))

;-----------------------------------------------------------------------
; Start a workflow from scratch or within the execution.
;-----------------------------------------------------------------------
(defn- start-workflow-execution
  ([wf-id]
   (try
     (let [wf-name (w/get-workflow-name wf-id)
           start-ts (util/now)
           {:keys[execution-id info]} (exs/setup-execution wf-id)]

       (add-exec-info! execution-id info wf-name start-ts)
       (publish-event {:event :execution-started
                       :execution-id execution-id
                       :start-ts start-ts
                       :wf-name wf-name})

       ; wiht-out-str
       (log/debug (clojure.pprint/pprint info))

       ; pass in nil for exec-vertex-id since the actual workflow is represented
       ; by the execution and is not a vertex in itself
       (start-workflow-execution nil (exm/root-workflow info) execution-id))
     (catch Exception e
       ; do something w/ this or let bubble up
       (log/error e))))

  ([exec-vertex-id exec-wf-id exec-id]
   (let [info (get-exec-info exec-id)
         roots (-> info (exm/workflow-graph exec-wf-id) g/roots)
         ts (util/now)]

     (db/workflow-started exec-wf-id ts)

     ; agent-id is nil because no agents are used to start a workflow vertex
     (if exec-vertex-id
       (db/execution-vertex-started exec-vertex-id nil ts))

     (publish-event {:event :wf-started
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
      (exm/dependencies exec-vertex-id success?)))

;-----------------------------------------------------------------------
; Creates a synthetic workflow to run the specified job in.
;-----------------------------------------------------------------------
(defn- run-job-as-synthetic-wf
  "Runs the job in the context of a synthetic workflow."
  [job-id]
  (let [{:keys[execution-id info]} (exs/setup-synthetic-execution job-id)
        start-ts (util/now)
        job-nm (->> info exm/vertices first (exm/vertex-attrs info) :node-nm)]

    (log/info "info: " info ", job-nm: " job-nm)
    (db/workflow-started (exm/root-workflow info) start-ts)
    (add-exec-info! execution-id info job-nm start-ts)
    (run-nodes (exm/vertices (get-exec-info execution-id))
               execution-id)))

; normalize the run-job as wf thing via as-workflow
(defn- trigger-node-execution
  [node-id]
  (let [{:keys[node-type-id]} (cache/node @node-sched-cache node-id)]
    (if (util/workflow-type? node-type-id)
      (start-workflow-execution node-id)
      (run-job-as-synthetic-wf node-id))))


(comment
(defn- abort-execution* [exec-id]
  (log/info "Aborting execution with id:" exec-id)
  (let [root-wf-id (-> exec-id get-exec-info exm/root-workflow)
        exec-name (get-execution-root-wf-name exec-id)
        start-ts (get-execution-start-ts exec-id)
        ts (util/now)]

    (ps/kill! exec-id)
    (ps/end-execution-tracking exec-id)

    (db/execution-aborted exec-id ts)

    (publish-event {:event :execution-finished
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
        start-ts (util/now)
        {:keys[info]} (w/resume-workflow-execution-data exec-id)]

    (add-exec-info! exec-id info wf-name start-ts)
    (db/update-execution-status exec-id db/started-status start-ts)

    (publish-event {:event :execution-started
                    :execution-id exec-id
                    :start-ts start-ts
                    :wf-name wf-name})

    (run-nodes [exec-vertex-id] exec-id)))

(defn resume-execution [exec-id exec-vertex-id]
  (if (execution-exists? exec-id)
    {:success? false :error "Execution is already in progress."}
    (do
      (resume-execution* exec-id exec-vertex-id)
      {:success? true}))))


(defn- execution-finished [exec-id success? last-exec-wf-id]
  (let [root-wf-id (-> exec-id get-exec-info exm/root-workflow)
        exec-name (get-execution-root-wf-name exec-id)
        start-ts (get-execution-start-ts exec-id)
        ts (util/now)]
    (db/workflow-finished root-wf-id success? ts)

    (db/execution-finished exec-id success? ts)
    (publish-event {:event :execution-finished
                    :execution-id exec-id
                    :success? success?
                    :status (if success? data/finished-success data/finished-error)
                    :finish-ts ts
                    :start-ts start-ts
                    :wf-name exec-name})

    (rm-exec-info! exec-id)))

(defn- parents-to-upd [vertex-id execution-id success?]
  (loop [v-id vertex-id ans {}]
    (let [{:keys[parent-vertex on-success on-failure belongs-to-wf]}
          (exm/vertex-attrs (get-exec-info execution-id) v-id)
          deps (if success? on-success on-failure)
          running-count (running-jobs-count execution-id belongs-to-wf)]
      (if (and parent-vertex (empty? deps) (zero? running-count))
        (recur parent-vertex (assoc ans parent-vertex belongs-to-wf))
        ans))))

(defn- mark-wf-and-parent-wfs-finished
  "Finds all parent workflows which also need to be marked as completed.
  NB exec-vertex-id can be nil if the workflow that finished is the root wf"
  [exec-vertex-id exec-wf-id execution-id success?]
  (let [ts (util/now)
        vertices-wf-map (parents-to-upd exec-vertex-id execution-id success?)
        vertices (if exec-vertex-id
                   (conj (keys vertices-wf-map) exec-vertex-id)
                   [])
        wfs (conj (vals vertices-wf-map) exec-wf-id)]
    (log/info "Marking finished for execution-id:" execution-id ", Vertices:" vertices ", wfs:" wfs)
    (db/workflows-and-vertices-finished vertices wfs success? ts)

    ; FIXME: need to iterate over all the vertices and publish-event
    (if vertices
      (publish-event {:event :wf-finished
                      :execution-id execution-id
                      :execution-vertices vertices
                      :finish-ts ts
                      :success? success?}))))


(defn- when-wf-finished [execution-id exec-wf-id exec-vertex-id]
  (let [wf-success? (exec-wf-success? execution-id exec-wf-id)
        tbl (get-exec-info execution-id)
        parent-vertex-id (exm/parent-vertex tbl exec-vertex-id)
        next-nodes (successor-nodes execution-id parent-vertex-id wf-success?)
        exec-failed? (and (not wf-success?) (empty? next-nodes))
        exec-success? (and wf-success? (empty? next-nodes))]

    (log/debug "execution-id " execution-id ", exec-wf-id" exec-wf-id ", exec-vertex-id" exec-vertex-id)
    (log/debug "wf-success?" wf-success? " next-nodes" next-nodes "exec-failed?" exec-failed? "exec-success?" exec-success?)


    (mark-wf-and-parent-wfs-finished exec-vertex-id exec-wf-id execution-id wf-success?)

    (run-nodes next-nodes execution-id)

    ; execution finished?
    (if (or exec-failed? exec-success?)
      (execution-finished execution-id exec-success? exec-wf-id))))

(defn- when-job-started
  "Logs the status to the db.  Increments the running job count for the execution id."
  [{:keys[execution-id exec-wf-id exec-vertex-id agent-id] :as data}]
  (db/execution-vertex-started exec-vertex-id agent-id (util/now))

  ; FIXME: 2 atoms being updated individually
  (update-running-jobs-count! execution-id exec-wf-id inc)
  (swap! tracker #(track/agent-started-job %1 agent-id exec-vertex-id (util/now)))

  ; FIXME: this doesn't have all the info like the job name, start-ts see old execution.clj
  (publish-event data))


; FIXME: update-running-jobs-count! is updating an atom and so is the code below put in dosync or what not
; Make idempotent, so if agent sends this again we don't blow up.
(defn- when-job-finished
  "Decrements the running job count for the execution id.
   Determines next set of jobs to run.
   Determines if the workflow is finished and/or errored.
   Sends job-finished-ack to agent.
   publish for distribution."
  [{:keys[execution-id exec-vertex-id agent-id exec-wf-id success? forced-by-conductor? error-msg] :as msg}]
  (log/debug "job-finished: " msg)

  ; update status in db
  (let [fin-status (if success? data/finished-success data/finished-error)
        fin-ts (util/now)]

    (db/execution-vertex-finished exec-vertex-id fin-status fin-ts)

    ; update status memory and ack the agent so it can clear it's memory
    ; agent-id can be null if the conductor is calling this method directly eg when no agent is available
    (when (not forced-by-conductor?)
      (swap! tracker #(track/rm-job %1 agent-id exec-vertex-id))
      (publish agent-id {:msg :job-finished-ack
                         :execution-id execution-id
                         :exec-vertex-id exec-vertex-id}))

    (let [new-count (if forced-by-conductor?
                      (running-jobs-count execution-id exec-wf-id)
                      (update-running-jobs-count! execution-id exec-wf-id dec))
          next-nodes (successor-nodes execution-id exec-vertex-id success?)
          exec-wf-fail? (and (not success?) (empty? next-nodes))
          exec-wf-finished? (and (zero? new-count) (empty? next-nodes))]

      (publish-event {:event :job-finished
                      :execution-id execution-id
                      :exec-vertex-id exec-vertex-id
                      :finish-ts fin-ts
                      :success? success?
                      :status fin-status
                      :error error-msg})

      (if exec-wf-fail?
        (mark-exec-wf-failed! execution-id exec-wf-id))

      (if exec-wf-finished?
        (when-wf-finished execution-id exec-wf-id exec-vertex-id)
        (run-nodes next-nodes execution-id)))))

;-----------------------------------------------------------------------
; -- Networked agents --
;-----------------------------------------------------------------------


(defn destroy []
  (quartz/stop))


(defn- zero-agents?
  "Answers true if there are no agents we know of."
  []
  (-> @tracker track/agents count zero?))

;-----------------------------------------------------------------------
; Dispatch for messages received from the sub socket.
;-----------------------------------------------------------------------
(defmulti dispatch :msg)

;-----------------------------------------------------------------------
; Add the agent and acknowledge.
;-----------------------------------------------------------------------
(defmethod dispatch :agent-registering [{:keys[agent-id]}]
  (log/info "Agent registering: " agent-id)
  (swap! tracker #(track/add-agent %1 agent-id (util/now)))
  (publish agent-id {:msg :agent-registered}))

;-----------------------------------------------------------------------
; If we know about the agent then, update the last heartbeat.
; Otherwise ask the agent to register. Agent could have registered,
; switch fails, heartbeats missed, conductor marks it as dead, remvoes
; from tracker, switch fixed, agent responds to heartbeat.
;
; REVIEW: This doesn't look right, @tracker, then swap!, what if agent
;         gets removed in between those times, then the swap! blows up?
;-----------------------------------------------------------------------
(defmethod dispatch :heartbeat-ack [{:keys[agent-id]}]
  (if (track/agent-exists? @tracker agent-id)
    (swap! tracker #(track/agent-heartbeat-rcvd %1 agent-id (util/now)))
    (publish agent-id {:msg :agents-register})))

;-----------------------------------------------------------------------
; Agent received the :run-job request, do what's required when a job
; is started.
;-----------------------------------------------------------------------
(defmethod dispatch :run-job-ack [data]
  (log/info "Run job ack:" data)
  (when-job-started data))

;-----------------------------------------------------------------------
; Agent says the job is finished.
;-----------------------------------------------------------------------
(defmethod dispatch :job-finished [data]
  (when-job-finished data))

; TODO: send an ack
(defmethod dispatch :node-save [{:keys[node-id]}]
  (let [n (db/get-node-by-id node-id)]
    (swap! node-sched-cache cache/put-node n)))

; TODO: send an ack,
; Cron expr could have changed, so update the triggers.
; Though you can check to see if they're different before you do.
(defmethod dispatch :schedule-save [{:keys[schedule-id]}]
  (let [{:keys[cron-expression] :as s} (db/get-schedule schedule-id)
        c (swap! node-sched-cache cache/put-schedule s)
        aa (cache/schedule-assocs-for-schedule c schedule-id)]
    (doseq [{:keys[node-schedule-id node-id]} aa]
      (quartz/schedule-cron-job! node-schedule-id node-id cron-expression))))

; TODO: send an ack
; Remove existing assoc from the cache
; add the new ones
; schedule new ones w/ quartz
(defmethod dispatch :schedule-assoc [{:keys[node-id]}]
  (let [orig-assocs (cache/schedule-assocs-for-node @node-sched-cache node-id)
        orig-assoc-ids (map :node-schedule-id orig-assocs)
        new-assocs (db/node-schedules-for-node node-id)
        assoc-upd-fn (fn[c]
                       (-> c
                           (cache/rm-assocs orig-assoc-ids)
                           (cache/put-assocs new-assocs)))
        c (swap! node-sched-cache assoc-upd-fn)]

    (quartz/rm-triggers! orig-assoc-ids)

    (doseq [{:keys[node-schedule-id schedule-id]} new-assocs
            :let [{:keys[cron-expression]} (cache/schedule c schedule-id)]]
      (quartz/schedule-cron-job! node-schedule-id node-id cron-expression))))



(defmethod dispatch :ping [{:keys[reply-to] :as data}]
  (publish reply-to {:msg :pong}))

(defmethod dispatch :trigger-node [{:keys[node-id]}]
  (trigger-node-execution node-id))

(comment
(defmethod dispatch :abort-execution [{:keys[execution-id]}]
  (abort-execution execution-id))

(defmethod dispatch :resume-execution [{:keys[execution-id exec-vertex-id]}]
  (resume-execution execution-id exec-vertex-id)))

;-----------------------------------------------------------------------
; This gets called if we don't have a handler setup for a msg type
;-----------------------------------------------------------------------
(defmethod dispatch :default [data]
  (log/warn "No method to handle data: " data))


;-----------------------------------------------------------------------
; Find dead agents, and mark their jobs as unknown status.
;-----------------------------------------------------------------------
(defn- run-dead-agent-check
  "Checks for dead agents based on last heartbeat received.
   Marks those agents who last sent heartbeats before now - interval-ts as dead.
   Removes dead agents so they can't be used for running jobs.
   Marks affected execution-vertices as in unknown state, but does not remove
   from exec-infos, in hope that when agent connects again it will update us.
   If agent doesn't know anything, timeout will have to kick in and fail the job.
   Email users about agent disconnect and affected exec-vertex ids"
  [interval-ms]
  (while true
    (let [ts-threshold (- (util/now) interval-ms)
          agent-job-map (track/dead-agents-job-map @tracker ts-threshold)
          dead-agents (keys agent-job-map)
          vertex-ids (reduce into #{} (vals agent-job-map))]


      (when (-> vertex-ids empty? not)
        (log/info "Dead agent check, dead agents:" dead-agents ", affected vertex-ids:" vertex-ids)

        ; mark status as unknown in db
        (db/update-execution-vertices-status vertex-ids data/unknown-status (util/now))

        ; remove from tracker
        (swap! tracker track/rm-agents dead-agents)

        (notify/dead-agents dead-agents vertex-ids))

      (Thread/sleep interval-ms))))


;-----------------------------------------------------------------------
; REVIEW: Should this be done with a watch?
;         Had to broadcast repeatedly because it seemed the first message
;         never made it to the agent.
;-----------------------------------------------------------------------
(defn- ensure-one-agent-connected
  "Ensures at least one agent is connected before exiting this method.
   Broadcasts a registration request every second so that any running agents can register."
  []
  (while (zero-agents?)
    (log/info "No agents connected. Broadcasting registration and waiting..")
    (put! publish-chan {:topic "broadcast" :data {:msg :agents-register}})
    (Thread/sleep 1000)))


;-----------------------------------------------------------------------
; Kicks off jobs/wfs when quartz says to.
;-----------------------------------------------------------------------
(defn- run-quartz-processing
  "Quartz puts messages on ch. This reads those messages and triggers the jobs/wfs."
  [ch]
  (go-loop [{:keys[node-id]} (<! ch)]
     (try
       (trigger-node-execution node-id)
       (catch Exception ex
         (log/error ex)))
     (recur (<! ch))))


(defn- run-heartbeats
  "Starts a new thread to put heartbeat messages on to ch everty t millisecs."
  [ch t]
  (util/start-thread "heartbeats"
                     (fn[]
                       (while true
                         (put! ch {:topic "broadcast" :data {:msg :heartbeat}})
                         (Thread/sleep t)))))


(defn- populate-cache
  "Loads all nodes, schedules and associations from the db.
   Doesn't load job data ie command-line, execution-directory etc.
   Probably should?"
  []
  (let [c (-> (cache/new-cache)
              (cache/put-nodes (db/ls-nodes))
              (cache/put-schedules (db/ls-schedules))
              (cache/put-assocs (db/ls-node-schedules)))]
    (reset! node-sched-cache c)))

;-----------------------------------------------------------------------
; Read node-schedules assocs and populate quartz.
;-----------------------------------------------------------------------
(defn- populate-quartz-triggers []
  (doseq [{:keys[node-schedule-id schedule-id node-id]} (cache/schedule-assocs @node-sched-cache)
         :let [{:keys[cron-expression]} (cache/schedule @node-sched-cache schedule-id)]]
    (quartz/schedule-cron-job! node-schedule-id node-id cron-expression)))

(defn init [pub-port sub-port]
  (log/info "Connecting to database.")
  (conf/init-db)


  (let [host "*" bind? true]
   (msg/relay-reads "request-processor" host sub-port bind? msg/all-topics dispatch)
   (msg/relay-writes publish-chan host pub-port bind?))

  ;----------------------------------------------------------------------
  ; REVIEW
  ; for some reason if heartbeats are started first things work otherwise not
  ;----------------------------------------------------------------------
  (run-heartbeats publish-chan (conf/heartbeats-interval-ms))
  
  (util/start-thread "dead-agent-checker" #(run-dead-agent-check (conf/heartbeats-dead-after-ms)))

  (log/info "Populating cache.")
  (populate-cache)

  (ensure-one-agent-connected)


  (log/info "Initializing Quartz.")
  (quartz/init quartz-chan)
  ; quartz puts stuff on the quartz-chan when a trigger is fired
  (run-quartz-processing quartz-chan)


  (log/info "Populating Quartz.")
  (populate-quartz-triggers)


  (log/info "Starting Quartz.")
  (quartz/start)
  (log/info "Conductor started successfully."))
