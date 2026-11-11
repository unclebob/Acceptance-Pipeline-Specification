(ns aps.cli.gherkin-heldout-split
  (:require [aps.gherkin :as gherkin]
            [aps.heldout :as heldout]
            [aps.json :as aps-json]
            [clojure.string :as str])
  (:gen-class))

(defn- split-opts [args]
  (loop [positional [] manifest nil in-repo-ok false [a & rest'] args]
    (cond
      (nil? a) {:positional positional :manifest manifest :in-repo-ok in-repo-ok}
      (= a "--in-repo-ok") (recur positional manifest true rest')
      (= a "--manifest") (if (seq rest')
                           (recur positional (first rest') in-repo-ok (rest rest'))
                           (throw (ex-info "--manifest requires a path" {})))
      (str/starts-with? a "--") (throw (ex-info (str "unknown option: " a) {}))
      :else (recur (conj positional a) manifest in-repo-ok rest'))))

(defn -main [& args]
  (let [{:keys [positional manifest in-repo-ok]}
        (try
          (split-opts args)
          (catch Exception e
            (binding [*out* *err*] (println (.getMessage e)))
            (System/exit 2)))]
    (when (or (< (count positional) 3) (> (count positional) 5))
      (binding [*out* *err*]
        (println "usage: gherkin-heldout-split <json-ir> <visible-output> <heldout-output> [heldout-fraction] [seed] [--manifest <path>] [--in-repo-ok]"))
      (System/exit 2))
    (try
      (let [[ir-path visible-path heldout-path fraction seed] positional
            heldout-fraction (if (str/blank? fraction)
                               heldout/default-heldout-fraction
                               (Double/parseDouble fraction))
            seed' (or (not-empty seed) heldout/default-seed)
            _ (when (and (not in-repo-ok) (heldout/inside-git-repo? heldout-path))
                (throw (ex-info
                        (str "refusing to write held-out IR inside a git repository: "
                             heldout-path
                             " (pass --in-repo-ok to override)")
                        {})))
            feature (aps-json/read-json-file ir-path)
            {:keys [visible heldout]} (heldout/split-scenarios
                                       feature
                                       heldout-fraction
                                       seed')]
        (aps-json/write-pretty-file! visible-path
                                     (gherkin/strip-feature-for-write visible))
        (aps-json/write-pretty-file! heldout-path
                                     (gherkin/strip-feature-for-write heldout))
        (when manifest
          (aps-json/write-pretty-file!
           manifest
           (heldout/build-manifest feature heldout-fraction seed' heldout))))
      (catch Exception e
        (binding [*out* *err*]
          (println (.getMessage e)))
        (System/exit 1)))))
