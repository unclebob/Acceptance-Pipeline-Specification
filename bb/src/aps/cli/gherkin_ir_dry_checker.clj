(ns aps.cli.gherkin-ir-dry-checker
  (:require [aps.dry :as dry]
            [aps.json :as aps-json])
  (:gen-class))

(defn -main [& args]
  (let [include-exact (some #{"--include-exact"} args)
        positional (remove #{"--include-exact"} args)]
    (if (not= 2 (count positional))
      (do
        (binding [*out* *err*]
          (println "usage: gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>"))
        (System/exit 2))
      (try
        (let [[input output] positional
              report (dry/analyze (aps-json/read-json-file input) {:include-exact include-exact})]
          (dry/write-json! output report))
        (catch Exception e
          (binding [*out* *err*]
            (println (.getMessage e)))
          (System/exit 1))))))
