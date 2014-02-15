(ns jsk.workflow
  (:require [jsk.plumb :as plumb]
            [jsk.util :as u]
            [jsk.rfn :as rfn]
            [jsk.schedule :as s]
            [enfocus.core :as ef]
            [enfocus.events :as events]
            [enfocus.effects :as effects]
            [clojure.string :as string]
            [goog.string :as gstring]
            [goog.string.format]
            [jayq.core :as jq])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(declare show-execution-workflow-details)

; stores for the current execution the execution workflow id and name
; with the root wf as the very first thing pushed on the stack
(def breadcrumb-wf-stack (atom []))

; for keeping track of what execution id we are looking at
(def current-execution-id (atom nil))

(defn- workflow-type? [node-type-id] (= 2 node-type-id))

(defn- show-element [sel]
  (-> sel $ .show))

(defn- hide-element [sel]
  (-> sel $ .hide))

(defn- node->layout-info [n]
  (let [node-id (ef/from n (ef/get-attr :data-node-id))
        css-text (-> n .-style .-cssText)]
    {:node-id (u/str->int node-id) :css-text css-text}))


(defn- collect-workflow-layout-info []
  (let [nodes ($ ".workflow-node")]
    (doall (map node->layout-info nodes))))


(defn- show-save-success []
  (show-element "#workflow-save-success")
  (ef/at "#workflow-save-success"  (effects/fade-out 1000)))



;----------------------------------------------------------------------
; Deletes all connections from the end points and removes the
; node from the visualizer.
;----------------------------------------------------------------------
(defn delete-workflow-node [node-id ep-fail-id ep-success-id]
  (plumb/rm-endpoint ep-fail-id)
  (plumb/rm-endpoint ep-success-id)
  (plumb/rm-inbound-connections node-id)
  (-> (str "#" node-id) $ .remove))


;----------------------------------------------------------------------
; src is a string like "ep-fail-node-id-1"
; tgt is a string like "node-id-2"
; Returns {:success? false :src-node-id 1 :tgt-node-id 2}
;----------------------------------------------------------------------
(defn- parse-connection-info [{:keys [src tgt]}]
  ;(u/log (str "src: " src ", tgt: " tgt))
  (let [tt (-> src (string/split #"-"))
        status (second tt)
        src-id (last tt)
        tgt-id (-> tgt (string/split #"-") last)]
    ;(u/log (str "status: " status ", src-id: " src-id ", tgt-id: " tgt-id))
    {:success? (= "success" status)
     :src-node-id (u/str->int src-id)
     :tgt-node-id (u/str->int tgt-id)}))



(defn- read-workflow-form []
  (let [form (ef/from "#workflow-save-form" (ef/read-form))
        data (u/update-str->int form :workflow-id)
        data1 (assoc data :is-enabled (u/element-checked? "workflow-is-enabled"))]
    ;(u/log (str "Form data is: " form))
    data1))


(defn- collect-workflow-data []
  (let [form (read-workflow-form)
        cn (map parse-connection-info (plumb/connections->map))]
    (assoc form :connections cn)))


;----------------------------------------------------------------------
; Saving a workflow requires a workflow name, and all nodes
; and connections from jsPlumb.
;----------------------------------------------------------------------
(defn save-workflow [event]
  (go
   (let [workflow-info (collect-workflow-data)
         layout-info   (collect-workflow-layout-info)
         data {:workflow workflow-info :layout layout-info}
         {:keys [success? schedule-id errors] :as save-result} (<! (rfn/save-workflow data))]
     (if success?
       (show-save-success)
       (u/display-errors (-> errors vals flatten))))))

(defn trigger-workflow-now [e]
  (.stopPropagation e)
  (let [source (u/event-source e)
        wf-id (ef/from source (ef/get-attr :data-workflow-id))]
    (rfn/trigger-workflow-now wf-id)))


(defn- status-id->glyph
  "Translates id to a glyphicon"
  [id]
  (case id
    1 ""                            ; unexecuted-status
    2 "glyphicon-flash"             ; started-status
    3 "glyphicon-ok"                ; finished-success
    4 "glyphicon-exclamation-sign"  ; finished-error
    5 "glyphicon-minus-sign"        ; aborted
    6 "glyphicon-question-sign"     ; unknown
    7 "glyphicon-time"              ; pending
    ))   ; unknown-status


;----------------------------------------------------------------------
; Layout of the entire visualizer screen.
;----------------------------------------------------------------------
(em/defsnippet workflow-visualizer :compiled "public/templates/workflow.html" "#workflow-visualizer" [workflow-id workflow-name workflow-desc enabled? node-dir-id]
  "#workflow-id" (ef/set-attr :value (str workflow-id))
  "#workflow-name" (ef/set-attr :value workflow-name)
  "#workflow-desc" (ef/set-attr :value workflow-desc)
  "#node-directory-id" (ef/set-attr :value (str node-dir-id))
  "#workflow-is-enabled" (ef/do->
                           (ef/set-prop "checked" enabled?)
                           (ef/set-attr :value (str enabled?)))
  "#workflow-save-action" (events/listen :click save-workflow)
  "#view-assoc-schedules" (if (= -1 workflow-id)
                            (ef/remove-node)
                            (events/listen :click #(s/show-schedule-assoc workflow-id))))

;----------------------------------------------------------------------
; Adding a new workflow node on the visualizer.
;----------------------------------------------------------------------
(em/defsnippet workflow-node :compiled "public/templates/workflow.html" "#workflow-node" [div-id node-id node-name success-div-id fail-div-id rm-btn-id]
  "div.workflow-node"          (ef/set-attr :id div-id :data-node-id node-id)
  "button"                     (ef/do->
                                 (ef/set-attr :id rm-btn-id :data-node-id node-id)
                                 (events/listen :click (fn[e] (delete-workflow-node div-id fail-div-id success-div-id))))
  "div.workflow-node-item-name" (ef/content node-name)
  "div.ep-designer-fail"        (ef/set-attr :data-node-id node-id :id fail-div-id)
  "div.ep-designer-success"     (ef/set-attr :data-node-id node-id :id success-div-id))




;----------------------------------------------------------------------
; Clicking on a connection for now will delete the connection.
;----------------------------------------------------------------------
(defn- connection-click-listener [cn]
  (plumb/detach-connection cn))

(defn- node-id->div-id
  "Constructs the div id for the whole node node."
  [node-id]
  (str "node-id-" node-id))


(defn- node-id->success-ep-id
  "Constructs the success endpoint div id"
  [node-id]
  (str "ep-success-" (node-id->div-id node-id)))

(defn- node-id->fail-ep-id
  "Constructs the fail endpoint div id"
  [node-id]
  (str "ep-fail-" (node-id->div-id node-id)))



;----------------------------------------------------------------------
; Make the delete button visible for the node (node).
;----------------------------------------------------------------------
(defn- node-hover-handler [node-sel rm-btn-sel]
  (-> node-sel $ (.mouseenter #(show-element rm-btn-sel))
                 (.mouseleave #(hide-element rm-btn-sel))))


;----------------------------------------------------------------------
; Creates a visible node in the visualizer.
; layout is the csstext property to apply to the node
;----------------------------------------------------------------------
(defn- visualizer-add-node*
  [node-id node-name layout]
  (let [node-id-str     (str node-id)
        div-id         (node-id->div-id node-id)
        div-sel        (str "#" div-id)
        ;;node-name       (get @node-store node-id)
        success-div-id (node-id->success-ep-id node-id)
        fail-div-id    (node-id->fail-ep-id node-id)
        rm-btn-id      (str "rm-node-id-" node-id-str)
        rm-btn-sel     (str "#" rm-btn-id)]

    ; right now we're only supporting adding one instance of a node in the workflow
    ;(when (-> div-sel $ count zero?)

      (ef/at "#workflow-visualization-area"
        (ef/append (workflow-node div-id node-id-str node-name success-div-id fail-div-id rm-btn-id)))

      (set! (-> div-sel $ first .-style .-cssText) layout)
      (node-hover-handler div-sel rm-btn-sel)
      (hide-element rm-btn-sel)
      (plumb/draggable div-sel {:containment :parent})
      (plumb/make-success-source (str "#" success-div-id))
      (plumb/make-failure-source (str "#" fail-div-id))
      (plumb/make-target div-sel)))



; The data in the input map is like:
; {:to-node-layout "top: 142px; left: 50.5px;"
;  :from-node-layout "top: 30px; left: 39.5px;"
;  :to-node-type-id 1
;  :to-node-name "run ls"
;  :from-node-type-id 1
;  :from-node-name "cal node"
;  :success true
;  :to-node-id 2
;  :from-node-id 1}
(defn- add-one-edge [{:keys[src-id src-name dest-id dest-name src-layout dest-layout success]}]
  (let [src-div-id (node-id->div-id src-id)
        dest-div-id   (node-id->div-id dest-id)]

    (when (u/element-not-exists? src-div-id)
      (visualizer-add-node* src-id src-name src-layout))

    (when (u/element-not-exists? dest-div-id)
      (visualizer-add-node* dest-id dest-name dest-layout))

  (let [id-mkr (if success node-id->success-ep-id node-id->fail-ep-id)
        src-ep-id (id-mkr src-id)]
    ; (u/log (str "making connection src: " src-ep-id ", to: " dest-div-id))
    (plumb/connect src-ep-id dest-div-id))))

(defn- reconstruct-ui [data]
  (doseq [m data]
    (add-one-edge m)))

(defn- when-jstree-node-dropped [e data]
  (def dnd-drop-data data)
  (let [clj-data (-> data .-data js->clj)
        element-id (-> (get clj-data "nodes") first)
        [node-type node-id] (u/explorer-element-id-dissect element-id)]
    (u/log (str "node dropped " element-id ", node-id: " node-id ", node-type: " node-type))
    (when (node-type #{:job :workflow})
      (go
       (let [{:keys [node-name]} (<! (rfn/fetch-node-info node-id))]
         (visualizer-add-node* node-id node-name ""))))))

(defn- when-jstree-node-moved [e data]
  (def dnd-move-data data)
  (u/log "node moved"))

(defn- when-jstree-node-started [e data]
  (def dnd-started-data data)
  (u/log "node started"))

(defn- enable-jstree-dnd []
  (-> js/document $ (.bind "dnd_start.vakata" when-jstree-node-started))
  (-> js/document $ (.bind "dnd_move.vakata" when-jstree-node-moved))
  (-> js/document $ (.bind "dnd_stop.vakata" when-jstree-node-dropped)))

;----------------------------------------------------------------------
; Shows a new workflow visualizer.
; -- Get and build the ui for all node in the system
;----------------------------------------------------------------------
(defn show-workflow-node-details [wf-id]
  (go
   (let [{:keys [workflow-id workflow-name workflow-desc is-enabled node-directory-id] :as wf} (<! (rfn/fetch-workflow-details wf-id)) 
         graph (<! (rfn/fetch-workflow-graph wf-id))]

     (plumb/reset) ; clear any state it may have had

     (u/show-explorer-node (workflow-visualizer workflow-id workflow-name workflow-desc is-enabled node-directory-id))
     (plumb/register-connection-click-handler connection-click-listener)

     ; these things can happen only after the workflow visualizer is displayed
     (hide-element "#workflow-save-success")
     (plumb/default-container :#workflow-visualization-area)
     (enable-jstree-dnd)
     (reconstruct-ui graph))))

;----------------------------------------------------------------------
;----------------------------------------------------------------------
;  Begin Execution visualization Section
;----------------------------------------------------------------------
;----------------------------------------------------------------------

;----------------------------------------------------------------------
; Layout the readonly visualization area.
;----------------------------------------------------------------------
(em/defsnippet execution-visualizer :compiled "public/templates/workflow.html" "#execution-visualizer" [{:keys[workflow-name execution-id status-id start-ts finish-ts]}]
  "#execution-id" (ef/content (str execution-id))
  "#workflow-name" (ef/content workflow-name)
  "#execution-status" (ef/content (u/status-id->desc status-id))
  "#start-ts" (ef/content (str start-ts))
  "#finish-ts" (ef/content (str finish-ts))
  "#execution-abort-action" (if (u/executing-status? status-id)
                              (events/listen :click (fn[event] (rfn/abort-execution execution-id)))
                              (ef/remove-node)))

;----------------------------------------------------------------------
; Adding a new execution node on the visualizer.
;----------------------------------------------------------------------
(em/defsnippet execution-node :compiled "public/templates/workflow.html" "#execution-node" [div-id node-id node-name success-div-id fail-div-id status-span-id status-id exec-wf-id]
  "div.workflow-node"          (ef/set-attr :id div-id :data-node-id node-id)
  "div.node-execution-status span.glyphicon" (ef/do->
                                               (ef/set-attr :id status-span-id)
                                               (ef/add-class (status-id->glyph status-id)))

  ; can drill down if exec-wf-id is not nil, ie  this is a workflow node
  "div.drill-down-execution-node"  (ef/do->
                                     (if exec-wf-id
                                       (events/listen :click (fn[e] (show-execution-workflow-details exec-wf-id)))
                                       (ef/remove-node)))

  "div.workflow-node-item-name" (ef/content node-name)
  "div.ep-exec-visualizer-fail" (ef/set-attr :data-node-id node-id :id fail-div-id)
  "div.ep-exec-visualizer-success" (ef/set-attr :data-node-id node-id :id success-div-id))


;----------------------------------------------------------------------
; Breadcrumb generator
;----------------------------------------------------------------------
(em/defsnippet wf-breadcrumb :compiled "public/templates/workflow.html" "#execution-breadcrumb" [wfs]
  "#execution-breadcrumb :not(li:first-child)" (ef/remove-node)
  "li" (em/clone-for [wf wfs]
         "a" (ef/do->
              (events/listen :click (fn[e] (show-execution-workflow-details (:exec-wf-id wf))))
              (ef/set-attr :data-wf-id (str (:exec-wf-id wf)))
              (ef/content (:wf-name wf)))))


(defn- execution-vertex-status-td-id [id]
  (str "execution-vertex-status-" id))

(defn- execution-vertex-start-td-id [id]
  (str "execution-vertex-start-ts-" id))

(defn- execution-vertex-finish-td-id [id]
  (str "execution-vertex-finish-ts-" id))

(defn- execution-vertex-glyphicon-span-id [id]
  (str "execution-vertex-glyph-span-id-" id))

(em/defsnippet execution-vertices :compiled "public/templates/workflow.html" "#execution-vertices-table" [vv]
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr" (em/clone-for [{:keys[execution-vertex-id node-nm start-ts status finish-ts]} vv]
                 "td.execution-vertex-id" (ef/content (str execution-vertex-id))
                 "td.execution-vertex-name" (ef/content node-nm )
                 "td.execution-vertex-status" (ef/do->
                                                (ef/set-attr :id (execution-vertex-status-td-id execution-vertex-id))
                                                (ef/content (u/status-id->desc status )))
                 "td.execution-vertex-start" (ef/do->
                                               (ef/set-attr :id (execution-vertex-start-td-id execution-vertex-id))
                                               (ef/content (str start-ts)))
                 "td.execution-vertex-finish" (ef/do->
                                                (ef/set-attr :id (execution-vertex-finish-td-id execution-vertex-id))
                                                (ef/content (str finish-ts)))

                 "td > a.execution-abort-action" (events/listen :click
                                                            (fn[e]
                                                              (rfn/abort-job @current-execution-id execution-vertex-id)))
                 "td > a.execution-resume-action" (events/listen :click
                                                            (fn[e]
                                                              (rfn/resume-execution @current-execution-id execution-vertex-id)))))



;----------------------------------------------------------------------
; Creates a visible node in the visualizer.
; layout is the csstext property to apply to the node
;----------------------------------------------------------------------
(defn- execution-visualizer-add-node
  [node-id node-name node-type exec-wf-id status-id layout]
  (let [node-id-str     (str node-id)
        div-id         (node-id->div-id node-id)
        div-sel        (str "#" div-id)
        success-div-id (node-id->success-ep-id node-id)
        fail-div-id    (node-id->fail-ep-id node-id)
        status-span-id (execution-vertex-glyphicon-span-id node-id)]

    ; right now we're only supporting adding one instance of a node in the workflow
    ;(when (-> div-sel $ count zero?)

    (ef/at "#execution-visualization-area"
      (ef/append (execution-node div-id node-id-str node-name success-div-id fail-div-id status-span-id status-id exec-wf-id)))

    (set! (-> div-sel $ first .-style .-cssText) (str "position: relative; " layout))
    (plumb/draggable div-sel {:containment :parent})
    (plumb/make-success-source (str "#" success-div-id))
    (plumb/make-failure-source (str "#" fail-div-id))
    (plumb/make-target div-sel)))



(defn- add-one-execution-edge
  "Adds vertices if needed and the execution edge to the visualizer"
  [{:keys[src-vertex-id src-node-name src-layout src-node-type src-runs-execution-workflow-id src-status-id
          dest-vertex-id dest-node-name dest-layout dest-node-type dest-runs-execution-workflow-id dest-status-id success]}]

  (let [src-div-id (node-id->div-id src-vertex-id)
        dest-div-id   (node-id->div-id dest-vertex-id)]

    (if (u/element-not-exists? src-div-id)
      (execution-visualizer-add-node src-vertex-id src-node-name src-node-type src-runs-execution-workflow-id src-status-id src-layout))

    (if (and dest-vertex-id (u/element-not-exists? dest-div-id))
      (execution-visualizer-add-node dest-vertex-id dest-node-name dest-node-type dest-runs-execution-workflow-id dest-status-id dest-layout))

    (if dest-vertex-id
      (let [id-mkr (if success node-id->success-ep-id node-id->fail-ep-id)
            src-ep-id (id-mkr src-vertex-id)]
        ; (u/log (str "making connection src: " src-ep-id ", to: " dest-div-id))
        (plumb/connect src-ep-id dest-div-id)))))


(defn- construct-execution-ui [{:keys[nodes wf-info]}]
  (doseq [m nodes]
    (add-one-execution-edge m)))


; turn this in to a protocol/ns thing
(defn- index-of [id stack]
  (loop [idx 0  [head & tail] stack]
    (cond
     (not head) -1
     (= id (:exec-wf-id head)) idx
     :else  (recur (inc idx) tail))))


; otherwise find where it is and pop up to that item
(defn- update-breadcrumb-wf-stack!
  "Pushes the data to the stack when the wf id is not on the stack.
   Otherwise pops till the id is reached"
  [exec-wf-id nm]
  (let [idx (index-of exec-wf-id @breadcrumb-wf-stack)]
    (if (= -1 idx)
      (swap! breadcrumb-wf-stack conj {:exec-wf-id exec-wf-id :wf-name nm})
      (swap! breadcrumb-wf-stack subvec 0 (inc idx)))))

; vv  is a seq of maps
(defn- collect-execution-details [vv]
  (->> (reduce (fn[ans {:keys[src-status-id src-vertex-id src-start-ts src-finish-ts src-node-name
                              dest-status-id dest-vertex-id dest-start-ts dest-finish-ts dest-node-name]}]
                 (assoc ans src-vertex-id
                            {:execution-vertex-id src-vertex-id
                             :start-ts src-start-ts
                             :finish-ts src-finish-ts
                             :node-nm src-node-name
                             :status src-status-id}
                            dest-vertex-id
                            {:execution-vertex-id dest-vertex-id
                             :start-ts dest-start-ts
                             :finish-ts dest-finish-ts
                             :node-nm dest-node-name
                             :status dest-status-id}))
              {}
              vv)
       vals
       (filter #(-> % :execution-vertex-id nil? not))))



(defn- show-execution-workflow-details [exec-wf-id]
  ;(u/log (str"exec-wf-id is:" exec-wf-id))
  (go
   (let [{:keys[wf-info nodes] :as exec-wf-info} (<! (rfn/fetch-execution-workflow-details exec-wf-id))]

     (ef/at "#execution-visualization-area" (ef/content "")) ; clear the existing content

     (update-breadcrumb-wf-stack! exec-wf-id (:workflow-name wf-info))

     ;(construct-execution-ui exec-wf-info)
     (ef/at "#execution-breadcrumb-div" (ef/content (wf-breadcrumb @breadcrumb-wf-stack)))
     (ef/at "#execution-vertices-info" (ef/content (execution-vertices (collect-execution-details nodes))))

     (plumb/reset) ; clear any state it may have had
     (plumb/default-container :#execution-visualization-area)
     (plumb/do-while-suspended  #(construct-execution-ui exec-wf-info)))))


(defn show-execution-visualizer
  [execution-id]
  ;(u/log (str "execution-id is:" execution-id))


  (reset! current-execution-id execution-id)
  (reset! breadcrumb-wf-stack [])  ; clear the state

  (go
   (let [{:keys[root-execution-workflow-id] :as exec-info}
         (<! (rfn/fetch-execution-details execution-id))]
     (u/showcase (execution-visualizer exec-info))
     (show-execution-workflow-details root-execution-workflow-id))))










(defn- update-ui-exec-vertex-start [exec-vertex-id start-ts status]
  (let [status-td-id (execution-vertex-status-td-id exec-vertex-id)
        start-td-id (execution-vertex-start-td-id exec-vertex-id)
        glyph-id (execution-vertex-glyphicon-span-id exec-vertex-id)
        glyph-sel (str "#" glyph-id)
        class-text (str "glyphicon " (status-id->glyph status))
        status-sel (str "#" status-td-id)
        start-sel (str "#" start-td-id)]
    (ef/at status-sel (ef/content (u/status-id->desc status)))
    (ef/at start-sel (ef/content (str start-ts)))
    (ef/at glyph-sel (ef/set-attr :class class-text))))

(defn- update-ui-exec-vertex-finish [exec-vertex-id finish-ts status]
  (let [status-td-id (execution-vertex-status-td-id exec-vertex-id)
        finish-td-id (execution-vertex-finish-td-id exec-vertex-id)
        glyph-id (execution-vertex-glyphicon-span-id exec-vertex-id)
        status-sel (str "#" status-td-id)
        finish-sel (str "#" finish-td-id)
        glyph-sel (str "#" glyph-id)
        class-text (str "glyphicon " (status-id->glyph status)) ]
    (ef/at status-sel (ef/content (u/status-id->desc status)))
    (ef/at finish-sel (ef/content (str finish-ts)))
    (ef/at glyph-sel (ef/set-attr :class class-text))))




;-----------------------------------------------------------------------
; Message dispatching.
; Maybe execution visualizer should be in a separate ns
;-----------------------------------------------------------------------
(defmulti dispatch :event)


(defmethod dispatch :wf-started [{:keys[exec-vertex-id start-ts]}]
  (update-ui-exec-vertex-start exec-vertex-id start-ts u/started-status))

; FIXME: the success? should be status!
(defmethod dispatch :wf-finished [{:keys[execution-vertices finish-ts success?]}]
  (let [status (if success? u/success-status u/error-status)]
    (doseq [exec-vertex-id execution-vertices]
      (update-ui-exec-vertex-finish exec-vertex-id finish-ts status))))

(defmethod dispatch :execution-finished [{:keys[status finish-ts]}]
  (ef/at "#execution-status" (ef/content (u/status-id->desc status)))
  (ef/at "#finish-ts" (ef/content (str finish-ts)))
  (ef/at "#execution-abort-action" (ef/remove-node)))

; notify the workflow/execution-visualizer that job is started
(defmethod dispatch :job-started [{:keys[execution-id exec-vertex-id start-ts]}]
  (update-ui-exec-vertex-start exec-vertex-id start-ts u/started-status))

; now need to update the glyphicons
;status-span-id (str "execution-status-node-id-" node-id)]
(defmethod dispatch :job-finished [{:keys[exec-vertex-id finish-ts status]}]
  (update-ui-exec-vertex-finish exec-vertex-id finish-ts status))


(defmethod dispatch :default [msg]) ; no-op since some msgs are handled by executions (need to refactor to execution-visualizer ns)

(defn event-received [{:keys[execution-id] :as msg}]
  (when (= execution-id @current-execution-id)
    (dispatch msg)))








