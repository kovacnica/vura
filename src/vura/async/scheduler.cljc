(ns vura.async.scheduler
  #?(:cljs (:require-macros [dreamcatcher.core :refer [safe]]
                            [cljs.core.async.macros :refer [go go-loop]]))
  (:require
    #?(:clj [dreamcatcher.core :refer [safe]])
    [vura.async.cron :refer [next-timestamp valid-timestamp?]]
    [vura.async.jobs :refer [start! stop! make-job
                             started? started-at?
                             finished? active?] :as j]
    #?(:clj [clojure.core.async :refer [go go-loop <! put! chan timeout alts! close!]]
       :cljs [cljs.core.async :refer [<! put! chan timeout alts! close!]])
    #?(:clj [clj-time.core :as t]
       :cljs [cljs-time.core :as t])
    #?(:clj [clj-time.local :refer (local-now)]
       :cljs [cljs-time.local :refer (local-now)])))


;; Schedule definitions
(defprotocol ScheduleActions
  (add-job [this job-name job ^String schedule] "Function adds Job. to this schedule")
  (remove-job [this job-name] "Function removes Job. form this schedule")
  (replace-job [this job-name new-job ^String schedule] "Function replaces Job. to this schedule")
  (reschedule-job [this job-name ^String schedule] "Function reschedules Job. with new schedule"))

(defprotocol ScheduleInfo
  (get-job [this job-name] "Returns Job. instance of job-name")
  (get-jobs [this] "Get all scheduled jobs names")
  (get-schedules [this] "List CRON schedules")
  (get-schedule [this job-name] "Returns CRON string for given job-name"))


(defn make-schedule
  "Functions returns reify instance. Jobs
  are supposed to be argument of 3ies.

  job-name, Job., CRON-schedule"
  [& jobs]
  (let [args (vec (partition 3 jobs))
        schedule-data (atom nil)
        schedule (reify
                   ScheduleInfo
                   (get-job [this job] (-> @schedule-data (get job) ::job))
                   (get-jobs [this] (-> @schedule-data keys))
                   (get-schedule [this job] (-> @schedule-data (get job) ::schedule))
                   (get-schedules [this] (let [jobs (-> @schedule-data keys)]
                                           (reduce merge (for [x jobs] (hash-map x (-> @schedule-data (get x) ::schedule))))))
                   ScheduleActions
                   (add-job [this job-name job s] (when-not (-> @schedule-data (get job-name)) (swap! schedule-data assoc job-name {::schedule s ::job job}) this))
                   (remove-job [this job-name] (swap! schedule-data dissoc job-name))
                   (replace-job [this job-name new-job s] (do
                                                            (remove-job this job-name)
                                                            (add-job this job-name new-job s)
                                                            this))
                   (reschedule-job [this job-name new-schedule] (do
                                                                  (swap! schedule-data assoc job-name {::schedule new-schedule ::job (-> @schedule-data (get job-name) ::job)})
                                                                  this)))]
    (doseq [[name job :as x] args]
      (do
        (assert (satisfies? j/JobInfo job) "Every instance of jobs has to implement JobInfo protocol.")
        (assert (satisfies? j/JobActions job) "Every instance of jobs has to implement JobActions protocol.")
        (apply add-job schedule x)))
    schedule))

;; Immutable

(defprotocol DispatcherActions
  (start-dispatching! [this] "Function activates dispatcher. Returns true if successfull or false if not.")
  (stop-dispatching! [this] "Function deactivates dispatcher. Always returns true")
  (disable-dispatcher [this] "Function disables dispatcher, stopping it from executing permenantly. Eaven if start-dispatching! is ran."))

(defn- period [a b]
  (t/in-millis (t/interval a b)))

(defn- wake-up-at? [schedule]
  (let [schedules (get-schedules schedule)
        timestamp (local-now)
        next-timestamps (for [[job-name job] schedules] (next-timestamp timestamp job))]
   (first (sort-by #(period timestamp %) next-timestamps))))

(defn- job-candidates? [schedule]
  (let [timestamp (local-now)]
    (remove nil?
            (for [[job-name job] (get-schedules schedule)]
              (when (valid-timestamp? timestamp job) job-name)))))


(defn make-dispatcher [schedule]
  (assert (satisfies? ScheduleInfo schedule) "Input data doesn't implement ScheduleInfo")
  (let [dispatch? (atom false)
        control-channel (chan)]
    ;; Dispatcher life cycle
    (go-loop []
             (let [[control-data] (alts! [control-channel (go (<! (timeout (period (local-now) (wake-up-at?  schedule)))) ::TIMEOUT)])]
               (when-not (nil? control-data)
                 (if-not @dispatch?
                   ;; If dispatcher is disabled and timeout occured
                   ;; wait for ::START
                   (if (= ::START (<! control-channel))
                     (do
                       (reset! dispatch? true)
                       (recur))
                     (recur))
                   ;; If dispatcher is running
                   (let [candidates (-> schedule job-candidates?)
                         jobs (map #(get-job schedule %) candidates)
                         finished-jobs (filter #(or (and (finished? %) (started? %)) (not (started? %))) jobs)]
                     (doseq [x finished-jobs]
                       (start! x))
                     (recur))))))
    (reify
      DispatcherActions
      (start-dispatching! [this]
        (do
          (reset! dispatch? true)
          (put! control-channel ::START)))
      (stop-dispatching! [this]
        (do
          (reset! dispatch? false)
          true))
      (disable-dispatcher [_] (close! control-channel)))))


(comment
  (def test-job (make-job
                  [:telling (safe  (println "Telling!"))
                   :throwning (safe (println "Throwing..."))]))

  (def another-job1 (make-job
                      [:drinking (safe  (println  "job1 drinking"))
                       :going-home (safe (println "job1 going home"))]))

  (def test-schedule (make-schedule
                       :test-job test-job "4/10 * * * * * *"
                       :another another-job1 "*/15 * * * * * *"))

  (def suicide-job (make-job
                     [:buying-rope (safe (println "@" (local-now)) (println "Suicide is buying a rope! Watch out!"))
                      :suicide (safe (println "Last goodbay!"))]))

  (def fetch-pe-configurations
    (make-job
      [:fetching (safe
                   (println "Fetching PE configurations: " (local-now)))]))


  (def night-watch
    (make-job
      [:night-wathcing (safe (println "Watching... At night!" (local-now)))]))

  (def device-monitoring
    (make-job
      [:device-monitoring (safe (println "Monitoring devices!" (local-now)))]))


  (def network-schedule
    (make-schedule
      :pe-fetching fetch-pe-configurations "0/20 * * * * * *"
      :night-watch night-watch "30 1 * * * * *"
      :device-monitoring device-monitoring "30 30-50 * * * * *"))


  (def krakken-dispatcher
    (make-dispatcher network-schedule)))
