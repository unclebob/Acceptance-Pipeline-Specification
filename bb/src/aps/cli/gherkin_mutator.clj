(ns aps.cli.gherkin-mutator
  (:require [aps.gherkin :as gherkin]
            [aps.mutation :as mutation]
            [clojure.string :as str])
  (:gen-class))

(def usage
  "usage: bb gherkin-mutator --runner-worker <command> [--feature <path>] [--work-dir <dir>] [--generated-dir <dir>] [--workers <n>] [--timeout <duration>] [--status-interval <duration>] [--level full|hard|soft] [--implementation-hash <hash>] [--json]")

(def help-text
  (str usage "\n"
       "\n"
       "Generate and run Gherkin acceptance-test mutations, then report whether\n"
       "the implementation and acceptance runner detect those mutations.\n"
       "\n"
       "Required option:\n"
       "  --runner-worker <command>       Command used to execute one generated\n"
       "                                  mutation. The command is split on spaces.\n"
       "\n"
       "Options:\n"
       "  --feature <path>                Feature file to mutate. Default:\n"
       "                                  features/a-feature.feature\n"
       "  --work-dir <dir>                Working directory for mutation state and\n"
       "                                  generated files. Default:\n"
       "                                  build/acceptance-mutation\n"
       "  --generated-dir <dir>           Existing generated implementation directory.\n"
       "                                  Defaults to <work-dir>/generated.\n"
       "  --workers <n>                   Number of mutation workers. Default: 1.\n"
       "  --timeout <duration>            Reserved timeout value. Durations accept\n"
       "                                  plain milliseconds, ms, s, or m.\n"
       "  --status-interval <duration>    How often status may be reported. Default: 30s.\n"
       "  --level full|hard|soft          Mutation level. Default: hard.\n"
       "  --implementation-hash <hash>    Override the implementation hash recorded\n"
       "                                  in mutation metadata.\n"
       "  --json                          Write the mutation report as JSON instead\n"
       "                                  of text.\n"
       "  -h, --help                      Print this help text and exit.\n"
       "\n"
       "Output:\n"
       "  Writes mutation metadata under <work-dir>/metadata and prints either a text\n"
       "  or JSON report. A successful report has zero Survived mutations and zero Errors.\n"
       "\n"
       "Exit codes:\n"
       "  0  All mutations were killed, or help was printed.\n"
       "  1  Mutations survived, runner errors occurred, or execution failed.\n"
       "  2  Invalid command line arguments.\n"))

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

(defn- help-request? [args]
  (boolean (some #{"--help" "-h"} args)))

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
    (println message)
    (println usage))
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
    (mutation/write-mutation-metadata! (:work-dir opts) (:feature opts) feature report implementation-hash (:level opts) (successful-report? report))
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
  (if (help-request? args)
    (println help-text)
    (try
      (let [opts (parse-args args)]
        (validate-options! opts)
        (run-mutator! opts))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*]
          (println (.getMessage e))
          (println usage))
        (System/exit 2))
      (catch Exception e
        (binding [*out* *err*]
          (println (.getMessage e)))
        (System/exit 1)))))
