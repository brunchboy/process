(ns babashka.process
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]))

(ns-unmap *ns* 'Process)

(set! *warn-on-reflection* true)

(defn- as-string-map
  "Helper to coerce a Clojure map with keyword keys into something coerceable to Map<String,String>

  Stringifies keyword keys, but otherwise doesn't try to do anything clever with values"
  [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [(str (if (keyword? k) (name k) k)) (str v)])) m)
    m))

(defn- set-env
  "Sets environment for a ProcessBuilder instance.
  Returns instance to participate in the thread-first macro."
  ^ProcessBuilder [^ProcessBuilder pb env]
  (doto (.environment pb)
    (.clear)
    (.putAll (as-string-map env)))
  pb)

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- debug [& strs]
  (binding [*out* *err*]
    (println (str/join " " strs))))

(defn check
  [proc]
  (let [exit-code @(:exit proc)]
    (if (not (zero? exit-code))
      (let [err (slurp (:err proc))]
        (throw (ex-info (if (string? err)
                          err
                          "failed")
                        (assoc proc :type ::error))))
      proc)))

(defrecord Process [proc exit in out err command]
  clojure.lang.IDeref
  (deref [this]
    (check this)))

(defmethod print-method Process [proc ^java.io.Writer w]
  (.write w (pr-str (into {} proc))))

(defn process
  ([command] (process command nil))
  ([command opts] (if (map? command)
                 (process command opts nil)
                 (process nil command opts)))
  ([prev command {:keys [:in  :in-enc
                         :out :out-enc
                         :err :err-enc
                         :dir
                         :env]}]
   (let [in (or in (:out prev))
         command (mapv str command)
         pb (cond-> (ProcessBuilder. ^java.util.List command)
              dir (.directory (io/file dir))
              env (set-env env)
              (identical? err :inherit) (.redirectError ProcessBuilder$Redirect/INHERIT)
              (identical? out :inherit) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (identical? in  :inherit) (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)
         stdin  (.getOutputStream proc)
         stdout (.getInputStream proc)
         stderr (.getErrorStream proc)]
     ;; wrap in futures, see https://github.com/clojure/clojure/commit/7def88afe28221ad78f8d045ddbd87b5230cb03e
     (when (and in (not (identical? :inherit out)))
       (future (with-open [stdin stdin] ;; needed to close stdin after writing
                 (io/copy in stdin :encoding in-enc))))
     (when (and out (not (identical? :inherit out)))
       (future (io/copy stdout out :encoding out-enc)))
     (when (and err (not (identical? :inherit err)))
       (future (io/copy stderr err :encoding err-enc)))
     (let [exit (delay (.waitFor proc))
           ;; bb doesn't support map->Process at the moment
           res (->Process proc
                          exit
                          stdin
                          stdout
                          stderr
                          command)]
       res))))
