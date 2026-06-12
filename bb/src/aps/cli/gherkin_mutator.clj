(ns aps.cli.gherkin-mutator
  (:require [aps.gherkin :as gherkin]
            [aps.mutation :as mutation]
            [clojure.string :as str])
  (:gen-class))

(def usage "usage: gherkin-mutator --runner-worker <command> [--feature <path>] [--work-dir <dir>] [--generated-dir <dir>] [--workers <n>] [--timeout <duration>] [--status-interval <duration>] [--level full|hard|soft] [--implementation-hash <hash>] [--json]")

(defn- parse-duration-ms [text]
  (cond
    (str/blank? text) 0
    (str/ends-with? text "ms") (Long/parseLong (subs text 0 (- (count text) 2)))
    (str/ends-with? text "s") (* 1000 (Long/parseLong (subs text 0 (dec (count text)))))
    (str/ends-with? text "m") (* 60000 (Long/parseLong (subs text 0 (dec (count text)))))
    :else (Long/parseLong text)))

(defn- parse-args [args]
  (loop [args args
         opts {:feature "features/a-feature.feature"
               :work-dir "build/acceptance-mutation"
               :generated-dir nil
               :workers 1
               :timeout ""
               :status-interval "30s"
               :level "hard"
               :runner-worker ""
               :implementation-hash nil
               :json false}]
    (if-let [arg (first args)]
      (case arg
        "--feature" (recur (nnext args) (assoc opts :feature (second args)))
        "--work-dir" (recur (nnext args) (assoc opts :work-dir (second args)))
        "--generated-dir" (recur (nnext args) (assoc opts :generated-dir (second args)))
        "--workers" (recur (nnext args) (assoc opts :workers (Long/parseLong (second args))))
        "--timeout" (recur (nnext args) (assoc opts :timeout (second args)))
        "--status-interval" (recur (nnext args) (assoc opts :status-interval (second args)))
        "--level" (recur (nnext args) (assoc opts :level (second args)))
        "--runner-worker" (recur (nnext args) (assoc opts :runner-worker (second args)))
        "--implementation-hash" (recur (nnext args) (assoc opts :implementation-hash (second args)))
        "--json" (recur (rest args) (assoc opts :json true))
        (throw (ex-info (str "unknown option " arg) {})))
      opts)))

(defn -main [& args]
  (try
    (let [opts (parse-args args)]
      (cond
        (not (#{"full" "hard" "soft"} (:level opts)))
        (do (binding [*out* *err*] (println "--level must be full, hard, or soft")) (System/exit 2))

        (str/blank? (:runner-worker opts))
        (do (binding [*out* *err*] (println "--runner-worker is required")) (System/exit 2))

        :else
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
                                    :runner-command (str/split (:runner-worker opts) #"\s+")})
              write-stamp? (and (zero? (get-in report [:summary :Survived] 0))
                                (zero? (get-in report [:summary :Errors] 0)))]
          (mutation/write-mutation-metadata! (:feature opts) feature report implementation-hash (:level opts) write-stamp?)
          (if (:json opts)
            (mutation/write-json-report! report)
            (mutation/write-text-report! report))
          (when (or (pos? (get-in report [:summary :Survived] 0))
                    (pos? (get-in report [:summary :Errors] 0)))
            (System/exit 1)))))
    (catch Exception e
      (binding [*out* *err*]
        (println (.getMessage e)))
      (System/exit 1))))
