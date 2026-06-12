(ns aps.cli.gherkin-mutator
  (:require [aps.gherkin :as gherkin]
            [aps.mutation :as mutation]
            [clojure.string :as str])
  (:gen-class))

(def usage "usage: gherkin-mutator --runner-worker <command> [--feature <path>] [--work-dir <dir>] [--generated-dir <dir>] [--workers <n>] [--timeout <duration>] [--status-interval <duration>] [--level full|hard|soft] [--implementation-hash <hash>] [--json]")

(declare successful-report? failed-report?)

(def default-duration-ms (Long/parseLong "0"))

(defn- parse-duration-ms [text]
  (let [[_ amount unit] (re-matches #"(\d+)(ms|s|m)?" (or text ""))]
    (if amount
      (* (Long/parseLong amount) (get {"ms" 1 "s" 1000 "m" 60000 nil 1} unit))
      default-duration-ms)))

(def default-options
  {:feature "features/a-feature.feature"
   :work-dir "build/acceptance-mutation"
   :generated-dir nil
   :workers 1
   :timeout ""
   :status-interval "30s"
   :level "hard"
   :runner-worker ""
   :implementation-hash nil
   :json false})

(def value-option-specs
  [{:flag "--feature" :key :feature}
   {:flag "--work-dir" :key :work-dir}
   {:flag "--generated-dir" :key :generated-dir}
   {:flag "--workers" :key :workers :parse #(Long/parseLong %)}
   {:flag "--timeout" :key :timeout}
   {:flag "--status-interval" :key :status-interval}
   {:flag "--level" :key :level}
   {:flag "--runner-worker" :key :runner-worker}
   {:flag "--implementation-hash" :key :implementation-hash}])

(defn- value-option-entry [{:keys [flag key parse]}]
  [flag [key (or parse identity)]])

(def value-options
  (into {} (map value-option-entry value-option-specs)))

(def flag-options
  {"--json" [:json true]})

(defn- apply-value-option [opts arg value]
  (if-let [[k parse] (value-options arg)]
    (assoc opts k (parse value))
    (throw (ex-info (str "unknown option " arg) {}))))

(defn- apply-option [opts args]
  (let [arg (first args)]
    (if-let [[k value] (flag-options arg)]
      [(assoc opts k value) (rest args)]
      [(apply-value-option opts arg (second args)) (nnext args)])))

(defn- parse-args [args]
  (loop [args args opts default-options]
    (if (seq args)
      (let [[opts' args'] (apply-option opts args)]
        (recur args' opts'))
      opts)))

(defn- usage-error [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 2))

(defn- validate-options! [opts]
  (when-not (#{"full" "hard" "soft"} (:level opts))
    (usage-error "--level must be full, hard, or soft"))
  (when (str/blank? (:runner-worker opts))
    (usage-error "--runner-worker is required")))

(defn- run-mutator! [opts]
  (let [feature (gherkin/parse-file (:feature opts))
        effective-generated-dir (or (:generated-dir opts)
                                    (str (:work-dir opts) "/generated"))
        implementation-hash (mutation/resolve-implementation-hash effective-generated-dir
                                                                   (:feature opts)
                                                                   (:implementation-hash opts))
        report (mutation/run {:feature feature
                              :feature-path (:feature opts)
                              :work-dir (:work-dir opts)
                              :generated-dir (:generated-dir opts)
                              :workers (:workers opts)
                              :level (:level opts)
                              :implementation-hash implementation-hash
                              :status-interval-ms (parse-duration-ms (:status-interval opts))
                              :runner-command (str/split (:runner-worker opts) #"\s+")})]
    (mutation/write-mutation-metadata! (:feature opts) feature report implementation-hash (:level opts) (successful-report? report))
    (if (:json opts)
      (mutation/write-json-report! report)
      (mutation/write-text-report! report))
    (when (failed-report? report)
      (System/exit 1))))

(defn- successful-report? [report]
  (and (zero? (get-in report [:summary :Survived] 0))
       (zero? (get-in report [:summary :Errors] 0))))

(defn- failed-report? [report]
  (not (successful-report? report)))

(defn -main [& args]
  (try
    (let [opts (parse-args args)]
      (validate-options! opts)
      (run-mutator! opts))
    (catch Exception e
      (binding [*out* *err*]
        (println (.getMessage e)))
      (System/exit 1))))