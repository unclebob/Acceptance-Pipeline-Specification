(ns aps.cli.gherkin-ir-dry-checker
  (:require [aps.dry :as dry]
            [aps.json :as aps-json])
  (:gen-class))

(def help-text
  (str "usage: bb gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>\n"
       "\n"
       "Analyze parsed Gherkin JSON IR for repeated or nearly repeated steps.\n"
       "\n"
       "Arguments:\n"
       "  <json-ir>        Path to JSON IR produced by bb gherkin-parser.\n"
       "  <report-output>  Path where the DRY analysis JSON report should be written.\n"
       "\n"
       "Options:\n"
       "  --include-exact  Include exact duplicate groups in the report. By default,\n"
       "                   exact duplicates are omitted so the report focuses on\n"
       "                   structural duplication and wording drift.\n"
       "  -h, --help       Print this help text and exit.\n"
       "\n"
       "Output:\n"
       "  Writes pretty JSON with a summary and findings. Findings include kind,\n"
       "  explanation, member steps, and locations for agent-readable remediation.\n"
       "\n"
       "Exit codes:\n"
       "  0  Wrote the DRY report, or printed help.\n"
       "  1  Failed to read, analyze, or write a file.\n"
       "  2  Invalid command line arguments.\n"))

(defn- help-request? [args]
  (boolean (some #{"--help" "-h"} args)))

(defn- print-usage-error! []
  (binding [*out* *err*]
    (println "usage: bb gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>"))
  (System/exit 2))

(defn -main [& args]
  (if (help-request? args)
    (println help-text)
    (let [include-exact (some #{"--include-exact"} args)
          positional (remove #{"--include-exact"} args)]
      (if (not= 2 (count positional))
        (print-usage-error!)
        (try
          (let [[input output] positional
                report (dry/analyze (aps-json/read-json-file input) {:include-exact include-exact})]
            (dry/write-json! output report))
          (catch Exception e
            (binding [*out* *err*]
              (println (.getMessage e)))
            (System/exit 1)))))))
