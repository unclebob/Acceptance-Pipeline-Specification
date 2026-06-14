(ns aps.cli.gherkin-parser
  (:require [aps.gherkin :as gherkin])
  (:gen-class))

(def help-text
  (str "usage: gherkin-parser <feature-file> <json-output>\n"
       "\n"
       "Parse a Gherkin feature file into the Acceptance Pipeline JSON IR.\n"
       "\n"
       "Arguments:\n"
       "  <feature-file>  Path to the input .feature file to parse.\n"
       "  <json-output>   Path where the parsed JSON IR should be written.\n"
       "\n"
       "Options:\n"
       "  -h, --help      Print this help text and exit.\n"
       "\n"
       "Output:\n"
       "  Writes pretty JSON containing the feature name, background steps,\n"
       "  scenarios, step keywords/text, parameters, and scenario examples.\n"
       "\n"
       "Exit codes:\n"
       "  0  Parsed and wrote the JSON IR, or printed help.\n"
       "  1  Failed to read, parse, or write a file.\n"
       "  2  Invalid command line arguments.\n"))

(defn- help-request? [args]
  (boolean (some #{"--help" "-h"} args)))

(defn- print-usage-error! []
  (binding [*out* *err*]
    (println "usage: gherkin-parser <feature-file> <json-output>"))
  (System/exit 2))

(defn -main [& args]
  (cond
    (help-request? args)
    (println help-text)

    (not= 2 (count args))
    (print-usage-error!)

    :else
    (try
      (gherkin/write-json! (second args) (gherkin/parse-file (first args)))
      (catch Exception e
        (binding [*out* *err*]
          (println (.getMessage e)))
        (System/exit 1)))))
