(ns aps.cli.gherkin-heldout-gate
  (:require [aps.heldout :as heldout]
            [aps.json :as aps-json]
            [clojure.string :as str])
  (:gen-class))

(defn- parse-args [args]
  (loop [positional [] journal nil [a & rest'] args]
    (cond
      (nil? a) {:positional positional :journal journal}
      (= a "--journal") (if (seq rest')
                          (recur positional (first rest') (rest rest'))
                          (throw (ex-info "--journal requires a path" {})))
      (str/starts-with? a "--") (throw (ex-info (str "unknown option: " a) {}))
      :else (recur (conj positional a) journal rest'))))

(defn -main [& args]
  (let [{:keys [positional journal]}
        (try (parse-args args)
             (catch Exception e
               (binding [*out* *err*] (println (.getMessage e)))
               (System/exit 2)))]
    (when (or (< (count positional) 2) (> (count positional) 3))
      (binding [*out* *err*]
        (println "usage: gherkin-heldout-gate [--journal <path>] <visible-results> <heldout-results> [max-gap]"))
      (System/exit 2))
    (try
      (let [[visible-path heldout-path max-gap] positional
            gap-limit (if (str/blank? max-gap)
                        heldout/default-max-gap
                        (Double/parseDouble max-gap))
            report (heldout/gate-report
                    (aps-json/read-json-file visible-path)
                    (aps-json/read-json-file heldout-path)
                    gap-limit)]
        (aps-json/write-pretty-out! report)
        (when journal
          (aps-json/append-jsonl! journal
                                  (assoc (select-keys report [:version :gate :visible :heldout :gap :gap-pp :max-gap :status])
                                         :timestamp (str (java.time.Instant/now)))))
        (System/exit (if (= "pass" (:status report)) 0 1)))
      (catch Exception e
        (binding [*out* *err*]
          (println (.getMessage e)))
        (System/exit 1)))))
