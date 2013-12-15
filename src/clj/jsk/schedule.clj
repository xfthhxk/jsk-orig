(ns jsk.schedule
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [jsk.db :as jdb]
            [jsk.util :as ju]
            [jsk.quartz :as q])
  (:use [korma core]
        [bouncer.validators :only [defvalidator]]
        [swiss-arrows core]))

(defentity schedule
  (pk :schedule-id)
  (entity-fields :schedule-id :schedule-name :schedule-desc :cron-expression))

;-----------------------------------------------------------------------
; Schedule lookups
;-----------------------------------------------------------------------
(defn ls-schedules
  []
  "Lists all schedules"
  (select schedule))

(defn get-schedule
  "Gets a schedule for the id specified"
  [id]
  (first (select schedule
          (where {:schedule-id id}))))

(defn get-schedules [ids]
  (select schedule
    (where {:schedule-id [in ids]})))

(defn get-schedule-by-name
  "Gets a schedule by name if one exists otherwise returns nil"
  [nm]
  (first (select schedule (where {:schedule-name nm}))))

(defn schedule-name-exists?
  "Answers true if schedule name exists"
  [nm]
  (-> nm get-schedule-by-name nil? not))

(def new-schedule-name? (complement schedule-name-exists?))


;-----------------------------------------------------------------------
; Validates if the s-name can be used
;-----------------------------------------------------------------------
(defn unique-name?  [id sname]
  (if-let [s (get-schedule-by-name sname)]
    (= id (:schedule-id s))
    true))

; NB the first is used to see if bouncer generated any errors
; bouncer returns a vector where the first item is a map of errors
(defn validate-save [{:keys [schedule-id] :as s}]
  (-> s
    (b/validate
       :schedule-name [v/required [(partial unique-name? schedule-id) :message "Schedule name must be unique."]]
       :cron-expression [v/required [q/cron-expr? :message "Invalid cron expression."]])
    first))

;-----------------------------------------------------------------------
; Insert a schedule
;-----------------------------------------------------------------------
(defn- insert-schedule! [m user-id]
  (let [merged-map (merge (dissoc m :schedule-id) {:creator-id user-id :updater-id user-id})]
    (info "Creating new schedule: " merged-map)
    (-> (insert schedule (values merged-map))
         jdb/extract-identity)))

;-----------------------------------------------------------------------
; Update an existing schedule
; Answers with the schedule-id if update is successful.
;-----------------------------------------------------------------------
(defn- update-schedule-db! [{:keys [schedule-id] :as m} user-id]
  (let [merged-map (merge m {:updater-id user-id :update-ts (jdb/now)})]
    (info "Updating schedule: " m)
    (update schedule (set-fields (dissoc m :schedule-id))
      (where {:schedule-id schedule-id})))
  schedule-id)


(defn- get-job-schedule-info [schedule-id]
  (exec-raw ["select
                     ns.node_schedule_id
                   , ns.node_id
                   , n.node_type_id
                   , s.cron_expression
                from
                     node_schedule ns
                join schedule     s
                  on ns.schedule_id = s.schedule_id
                join node n
                  on ns.node_id = n.node_id
               where ns.schedule_id = ?"
             [schedule-id]]
            :results))


(defn- update-schedule-quartz! [schedule-id]
  (doseq [{:keys[node-schedule-id node-id node-type-id cron-expression]} (get-job-schedule-info schedule-id)]
    (q/update-trigger! node-schedule-id node-id node-type-id cron-expression)))

(defn- update-schedule! [{:keys [schedule-id] :as s} user-id]
  (update-schedule-db! s user-id)
  (update-schedule-quartz! schedule-id))


(defn- save-schedule* [{:keys [schedule-id] :as s} user-id]
  (-<>  (if (jdb/id? schedule-id)
          (update-schedule! s user-id)
          (insert-schedule! s user-id))
        {:success? true :schedule-id <>}))


;-----------------------------------------------------------------------
; Saves the schedule either inserting or updating depending on the
; schedule-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-schedule! [s user-id]
  (if-let [errors (validate-save s)]
    (ju/make-error-response errors)
    (save-schedule* s user-id)))
