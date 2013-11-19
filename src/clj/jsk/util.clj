(ns jsk.util
  (:require [jsk.user :as juser]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal)]))


(defn validation-errors? [bouncer-result]
  (-> bouncer-result first nil? not))

(defn extract-validation-errors [bouncer-result]
  (first bouncer-result))

(defn make-error-response [errors]
  {:success? false :errors errors})


(def app-edn "application/edn")

(defn edn-request? [r]
  (let [ct (:content-type r)]
    (if ct
      (not= -1 (.indexOf ct app-edn))
      false)))

(defn nan? [x]
  (Double/isNaN x))

(def not-nan (complement nan?))


(defn ensure-directory [dir]
  (-> dir io/file .mkdirs))


(defn str->int [s]
  (Integer/parseInt s))
