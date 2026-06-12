(ns aps.mutation
  (:require [aps.gherkin :as gherkin]
            [aps.json :as aps-json]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.lang ProcessBuilder$Redirect]
           [java.util.concurrent Executors TimeUnit Callable]))

(defn- utf8-bytes [s] (.getBytes (str s) StandardCharsets/UTF_8))

(defn- fnv64a [& parts]
  (let [modulus (biginteger 18446744073709551616N)
        prime (biginteger 1099511628211N)]
    (reduce
     (fn [h b]
       (.mod (.multiply (.xor (biginteger h) (biginteger (bit-and b 0xff))) prime) modulus))
     (biginteger 14695981039346656037N)
     (mapcat #(concat (utf8-bytes %) [0]) parts))))

(defn- parse-int [s]
  (try
    (when (seq s) (Long/parseLong s))
    (catch Exception _ nil)))

(defn- parse-float [s]
  (try
    (when (str/includes? s ".")
      (let [f (Double/parseDouble s)]
        (when (and (not (Double/isInfinite f)) (not (Double/isNaN f))) f)))
    (catch Exception _ nil)))

(declare mutate-value)

(defn- dither [path value]
  (if (empty? value)
    "x"
    (let [chars (vec value)
          index (int (mod (fnv64a path value) (count chars)))
          ch (chars index)
          replacement (cond
                        (<= (int \a) (int ch) (int \z)) (char (+ (int \A) (- (int ch) (int \a))))
                        (<= (int \A) (int ch) (int \Z)) (char (+ (int \a) (- (int ch) (int \A))))
                        :else \x)]
      (apply str (assoc chars index replacement)))))

(defn mutate-value [path value]
  (let [trimmed (str/trim value)
        lower (str/lower-case trimmed)
        seed (fnv64a path value)]
    (cond
      (str/includes? trimmed ",")
      (let [parts (mapv str/trim (str/split trimmed #","))
            index (int (mod seed (count parts)))]
        (str/join ", " (assoc parts index (mutate-value (str path "[]") (parts index)))))

      (= lower "true") "false"
      (= lower "false") "true"
      (#{"null" "nil" "none"} lower) "value"

      (some? (parse-int trimmed))
      (let [i (parse-int trimmed)
            delta (if (zero? (mod seed 2))
                    (- (inc (mod seed 9)))
                    (inc (mod seed 9)))]
        (str (+ i delta)))

      (some? (parse-float trimmed))
      (let [f (parse-float trimmed)
            delta (double (/ (+ (mod seed 900) 100) 100))
            delta (if (zero? (mod seed 2)) (- delta) delta)
            result (+ f delta)]
        (str result))

      :else (dither path value))))

(defn discover [feature]
  (vec
   (map-indexed
    (fn [i mutation] (assoc mutation :ID (str "m" (inc i))))
    (for [[scenario-index scenario] (map-indexed vector (:scenarios feature))
          [example-index example] (map-indexed vector (:examples scenario))
          key (sort (keys example))
          :let [original (get example key)
                path (format "$.scenarios[%d].examples[%d].%s" scenario-index example-index (name key))
                mutated (mutate-value path original)]
          :when (not= mutated original)]
      (array-map :Path path
                 :Description (format "%s: %s -> %s" path original mutated)
                 :Original original
                 :Mutated mutated
                 :scenario scenario-index
                 :example example-index
                 :key key)))))

(defn apply-mutation [feature mutation]
  (assoc-in feature [:scenarios (:scenario mutation) :examples (:example mutation) (:key mutation)]
            (:Mutated mutation)))

(defn- mutation-view [mutation]
  (array-map :ID (:ID mutation)
             :Path (:Path mutation)
             :Description (:Description mutation)
             :Original (:Original mutation)
             :Mutated (:Mutated mutation)))

(defn make-result [mutation runner-result]
  (let [status (case (:outcome runner-result)
                 "test_failure" "killed"
                 "test_success" "survived"
                 "infrastructure_error" "error"
                 "error")]
    (array-map :Mutation (mutation-view mutation)
               :Status status
               :Output (or (:output runner-result) "")
               :Error (or (:error runner-result) "")
               :Duration (long (or (:duration runner-result) 0)))))

(defn- write-feature-json! [path feature]
  (gherkin/write-json! path feature))

(defn- process-builder [command]
  (doto (ProcessBuilder. ^java.util.List command)
    (.redirectError ProcessBuilder$Redirect/INHERIT)))

(defn- start-worker [command]
  (let [process (.start (process-builder command))]
    {:process process
     :lock (Object.)
     :reader (BufferedReader. (InputStreamReader. (.getInputStream process) StandardCharsets/UTF_8))
     :writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream process) StandardCharsets/UTF_8))}))

(defn- close-worker! [{:keys [process writer]}]
  (try (.close writer) (catch Exception _))
  (try (.waitFor process 100 TimeUnit/MILLISECONDS) (catch Exception _))
  (when (.isAlive process)
    (.destroy process)))

(defn- run-worker-job [worker job]
  (locking (:lock worker)
    (let [started (System/nanoTime)
          request (array-map :id (get-in job [:mutation :ID])
                             :feature_json (:feature-json job)
                             :generated_dir (:generated-dir job)
                             :work_dir (:work-dir job))]
      (try
        (.write (:writer worker) (json/generate-string request))
        (.newLine (:writer worker))
        (.flush (:writer worker))
        (if-let [line (.readLine (:reader worker))]
          (let [response (json/parse-string line true)
                duration (long (or (:duration response)
                                   (- (System/nanoTime) started)))]
            (if (= (:id response) (:id request))
              {:outcome (:outcome response)
               :output (or (:output response) "")
               :error (or (:error response) "")
               :duration duration}
              {:outcome "infrastructure_error"
               :error (format "worker response id %s does not match request id %s" (pr-str (:id response)) (pr-str (:id request)))
               :duration duration}))
          {:outcome "infrastructure_error"
           :error "worker exited without response"
           :duration (- (System/nanoTime) started)})
        (catch Exception e
          {:outcome "infrastructure_error"
           :error (.getMessage e)
           :duration (- (System/nanoTime) started)})))))

(defn- empty-summary []
  (array-map :Total 0 :Killed 0 :Survived 0 :Errors 0))

(defn- increment-summary [summary status]
  (case status
    "killed" (update summary :Killed inc)
    "survived" (update summary :Survived inc)
    "error" (update summary :Errors inc)
    summary))

(defn- completed [summary]
  (+ (:Killed summary 0) (:Survived summary 0) (:Errors summary 0)))

(defn- status-line [started summary running skipped-scenarios skipped-mutations]
  (let [elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
    (str "status elapsed=" elapsed-ms "ms"
         " total=" (:Total summary)
         " completed=" (completed summary)
         " running=" running
         " killed=" (:Killed summary)
         " survived=" (:Survived summary)
         " errors=" (:Errors summary)
         (when (or (pos? skipped-scenarios) (pos? skipped-mutations))
           (str " skipped_scenarios=" skipped-scenarios
                " skipped_mutations=" skipped-mutations)))))

(defn- sha256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (utf8-bytes s))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- strip-mutation-metadata [content]
  (let [state (reduce
               (fn [{:keys [in-manifest lines] :as state} line]
                 (let [trimmed (str/trim line)]
                   (cond
                     (str/starts-with? trimmed "# mutation-stamp:")
                     state

                     (= trimmed "# acceptance-mutation-manifest-begin")
                     (assoc state :in-manifest true)

                     (= trimmed "# acceptance-mutation-manifest-end")
                     (assoc state :in-manifest false)

                     in-manifest
                     state

                     :else
                     (assoc state :lines (conj lines line)))))
               {:in-manifest false :lines []}
               (str/split content #"\n" -1))]
    (str/replace (str/join "\n" (:lines state)) #"^\n+" "")))

(defn- read-mutation-metadata [feature-path]
  (try
    (let [content (slurp feature-path)
          lines (str/split content #"\n")
          parsed (reduce
                  (fn [{:keys [in-manifest manifest-lines] :as state} line]
                    (let [trimmed (str/trim line)]
                      (cond
                        (str/starts-with? trimmed "# mutation-stamp: sha256=")
                        (assoc state :stamp (subs trimmed (count "# mutation-stamp: sha256=")))

                        (= trimmed "# acceptance-mutation-manifest-begin")
                        (assoc state :in-manifest true)

                        (= trimmed "# acceptance-mutation-manifest-end")
                        (assoc state :in-manifest false)

                        in-manifest
                        (assoc state :manifest-lines
                               (conj manifest-lines (str/trim (str/replace trimmed #"^#" ""))))

                        :else state)))
                  {:in-manifest false :manifest-lines [] :stamp ""}
                  lines)]
      (if (seq (:manifest-lines parsed))
        {:stamp (:stamp parsed)
         :manifest (json/parse-string (str/join "" (:manifest-lines parsed)) true)}
        (when (seq (:stamp parsed))
          {:stamp (:stamp parsed) :manifest {}})))
    (catch Exception _ nil)))

(defn- hash-json [value]
  (sha256 (json/generate-string value)))

(defn- mutation-count-for-scenario [mutations scenario-index]
  (count (filter #(= scenario-index (:scenario %)) mutations)))

(defn- scenario-index-from-path [path]
  (when-let [[_ index] (re-find #"^\$\.scenarios\[(\d+)\]" path)]
    (Long/parseLong index)))

(defn- scenario-summaries [feature report]
  (if (and (empty? (:results report))
           (= 1 (count (:scenarios feature)))
           (pos? (get-in report [:summary :Total] 0)))
    {0 (:summary report)}
    (reduce (fn [summaries result]
              (if-let [scenario-index (scenario-index-from-path (get-in result [:Mutation :Path]))]
                (-> summaries
                    (update scenario-index (fnil update (empty-summary)) :Total inc)
                    (update scenario-index increment-summary (:Status result)))
                summaries))
            {}
            (:results report))))

(defn- new-manifest [feature-path feature report implementation-hash]
  (let [now (str (Instant/now))
        all-mutations (discover feature)
        summaries (scenario-summaries feature report)]
    (array-map
     :version 1
     :tested_at now
     :feature_name (:name feature)
     :feature_path feature-path
     :background_hash (hash-json (:background feature))
     :implementation_hash implementation-hash
     :scenarios
     (vec
      (keep-indexed
       (fn [i scenario]
         (let [summary (summaries i)]
           (when (and summary (zero? (:Survived summary 0)) (zero? (:Errors summary 0)))
             (array-map :index i
                        :name (:name scenario)
                        :scenario_hash (hash-json scenario)
                        :mutation_count (mutation-count-for-scenario all-mutations i)
                        :result summary
                        :tested_at now))))
       (:scenarios feature))))))

(defn- manifest-entry-reusable? [old current entry level feature mutations]
  (and (= 1 (:version old))
       (= (:feature_name old) (:feature_name current))
       (= (:feature_path old) (:feature_path current))
       (= (:background_hash old) (:background_hash current))
       (or (not= level "hard") (= (:implementation_hash old) (:implementation_hash current)))
       (<= 0 (:index entry) (dec (count (:scenarios feature))))
       (let [scenario (get (:scenarios feature) (:index entry))]
         (and (= (:name entry) (:name scenario))
              (= (:scenario_hash entry) (hash-json scenario))))
       (zero? (get-in entry [:result :Survived] 0))
       (zero? (get-in entry [:result :Errors] 0))
       (= (:mutation_count entry) (mutation-count-for-scenario mutations (:index entry)))))

(defn- merge-reusable-previous-scenarios [current previous feature level]
  (let [existing (set (map :index (:scenarios current)))
        mutations (discover feature)
        reusable (remove #(existing (:index %))
                         (filter #(manifest-entry-reusable? previous current % level feature mutations)
                                 (:scenarios previous)))]
    (update current :scenarios into reusable)))

(defn write-mutation-metadata! [feature-path feature report implementation-hash level write-stamp?]
  (let [content (slurp feature-path)
        previous (read-mutation-metadata feature-path)
        cleaned (strip-mutation-metadata content)
        stamp (sha256 cleaned)
        manifest (cond-> (new-manifest feature-path feature report implementation-hash)
                   previous (merge-reusable-previous-scenarios (:manifest previous) feature level))
        manifest-json (json/generate-string manifest)
        metadata (str (when write-stamp?
                        (str "# mutation-stamp: sha256=" stamp "\n"))
                      "# acceptance-mutation-manifest-begin\n"
                      "# " manifest-json "\n"
                      "# acceptance-mutation-manifest-end\n\n"
                      (str/replace cleaned #"^\n+" ""))]
    (spit feature-path metadata)))

(defn- feature-stamp-valid? [feature-path]
  (when-let [metadata (read-mutation-metadata feature-path)]
    (and (seq (:stamp metadata))
         (= (:stamp metadata) (sha256 (strip-mutation-metadata (slurp feature-path)))))))

(defn- accepted-skips [cfg mutations]
  (if (or (= (:level cfg) "full") (str/blank? (:feature-path cfg)))
    #{}
    (if-let [metadata (read-mutation-metadata (:feature-path cfg))]
      (if (and (empty? (get-in metadata [:manifest :scenarios]))
               (feature-stamp-valid? (:feature-path cfg)))
        (set (range (count (get-in cfg [:feature :scenarios]))))
        (let [current (new-manifest (:feature-path cfg) (:feature cfg) {:summary (empty-summary) :results []}
                                    (:implementation-hash cfg))]
          (set (map :index
                    (filter #(manifest-entry-reusable? (:manifest metadata) current % (:level cfg) (:feature cfg) mutations)
                            (get-in metadata [:manifest :scenarios]))))))
      #{})))

(defn- report-text [report]
  (let [summary (:summary report)
        header (str "total=" (:Total summary)
                    " killed=" (:Killed summary)
                    " survived=" (:Survived summary)
                    " errors=" (:Errors summary)
                    (when (or (pos? (:SkippedScenarios summary 0))
                              (pos? (:SkippedMutations summary 0)))
                      (str " skipped_scenarios=" (:SkippedScenarios summary 0)
                           " skipped_mutations=" (:SkippedMutations summary 0))))]
    (str header "\n"
         (apply str
                (for [result (:results report)]
                  (str (format "%-8s %s\n" (:Status result) (get-in result [:Mutation :Description]))
                       (when (or (= "survived" (:Status result)) (= "error" (:Status result)))
                         (str (when (seq (:Error result))
                                (str "  error: " (:Error result) "\n"))
                              (when (seq (:Output result))
                                (str "  output:\n" (:Output result) "\n"))))))))))

(defn write-text-report! [report]
  (print (report-text report)))

(defn write-json-report! [report]
  (aps-json/write-pretty-out! (aps-json/strip-empty-keys #{:SkippedScenarios :SkippedMutations} report)))

(defn- feature-metadata-slug [feature-path]
  (-> (reduce (fn [{:keys [s hyphen?]} ch]
                (let [c (Character/toLowerCase ^char ch)]
                  (if (or (<= (int \a) (int c) (int \z))
                          (<= (int \0) (int c) (int \9)))
                    {:s (str s c) :hyphen? false}
                    (if (and (not hyphen?) (seq s))
                      {:s (str s "-") :hyphen? true}
                      {:s s :hyphen? hyphen?}))))
              {:s "" :hyphen? false}
              feature-path)
      :s
      (str/replace #"^-+|-+$" "")))

(defn resolve-implementation-hash [generated-dir feature-path override]
  (if (seq override)
    override
    (let [path (str (io/file generated-dir "metadata" (str (feature-metadata-slug feature-path) ".json")))]
      (try
        (let [metadata (aps-json/read-json-file path)]
          (if (= (:feature_path metadata) feature-path)
            (or (:implementation_hash metadata) "unknown")
            "unknown"))
        (catch Exception _ "unknown")))))

(defn run [cfg]
  (let [cfg (merge {:workers 1
                    :work-dir "build/acceptance-mutation"
                    :level "hard"
                    :generated-dir nil
                    :status-interval-ms 30000}
                   cfg)
        generated-dir (or (:generated-dir cfg) (str (io/file (:work-dir cfg) "generated")))
        mutations (discover (:feature cfg))
        skip (accepted-skips cfg mutations)
        executable-indexes (vec (keep-indexed (fn [i mutation] (when-not (skip (:scenario mutation)) i)) mutations))
        skipped-scenarios (count skip)
        skipped-mutations (- (count mutations) (count executable-indexes))
        summary0 (assoc (empty-summary) :Total (count executable-indexes))
        results (atom (vec (repeat (count executable-indexes) nil)))
        summary (atom summary0)
        running (atom 0)
        started (System/nanoTime)
        workers (mapv start-worker (repeat (max 1 (:workers cfg)) (:runner-command cfg)))
        executor (Executors/newFixedThreadPool (max 1 (:workers cfg)))]
    (try
      (write-feature-json! (str (io/file (:work-dir cfg) "base" "feature.json")) (:feature cfg))
      (when (pos? (:status-interval-ms cfg))
        (binding [*out* *err*]
          (println (status-line started @summary @running skipped-scenarios skipped-mutations))))
      (let [futures
            (doall
             (map-indexed
              (fn [result-index mutation-index]
                (.submit executor
                         (reify Callable
                           (call [_]
                             (let [mutation (mutations mutation-index)
                                   mutation-work-dir (str (io/file (:work-dir cfg) "mutations" (:ID mutation)))
                                   feature-json (str (io/file mutation-work-dir "feature.json"))
                                   worker (workers (mod result-index (count workers)))]
                               (swap! running inc)
                               (try
                                 (write-feature-json! feature-json (apply-mutation (:feature cfg) mutation))
                                 (let [runner-result (run-worker-job worker {:mutation mutation
                                                                             :feature-json feature-json
                                                                             :generated-dir generated-dir
                                                                             :work-dir mutation-work-dir})
                                       result (make-result mutation runner-result)]
                                   (swap! results assoc result-index result)
                                   (swap! summary increment-summary (:Status result)))
                                 (catch Exception e
                                   (let [result (make-result mutation {:outcome "infrastructure_error"
                                                                       :error (.getMessage e)})]
                                     (swap! results assoc result-index result)
                                     (swap! summary increment-summary "error")))
                                 (finally
                                   (swap! running dec))))))))
              executable-indexes))]
        (doseq [f futures] (.get f)))
      (let [final-summary (cond-> @summary
                            (pos? skipped-scenarios) (assoc :SkippedScenarios skipped-scenarios)
                            (pos? skipped-mutations) (assoc :SkippedMutations skipped-mutations))
            report (array-map :summary final-summary :results (vec (remove nil? @results)))]
        (binding [*out* *err*]
          (when (pos? (:status-interval-ms cfg))
            (println (status-line started final-summary @running skipped-scenarios skipped-mutations))))
        report)
      (finally
        (.shutdown executor)
        (doseq [worker workers] (close-worker! worker))))))
