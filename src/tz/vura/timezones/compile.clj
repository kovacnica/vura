(ns vura.timezones.compile
  (:require
    [clojure.java.io :as io]
    [me.raynes.fs :as fs]
    [me.raynes.fs.compression 
     :refer [gunzip untar]]))



;; Clear db.cljc with default empty db.init file
(fs/copy+ (io/resource "db.init") "src/core/vura/timezones/db.cljc")

;; Require parser
(require '[vura.timezones.parser :as parser])


(def uri "https://data.iana.org/time-zones/tzdata-latest.tar.gz")


(defn download 
  "Function downloads latest tzdata file and returns file reference"
  []
  (let [f (fs/temp-file "timezones" ".tar.gz")]
    (with-open [i (io/input-stream (java.net.URI. uri))
                o (io/output-stream f)]
      (io/copy i o))
    f))

(defn extract
  [f]
  (let [f' (fs/temp-file "timezones" ".tar")
        f'' (fs/temp-dir "timezones")] 
    (gunzip f f')
    (untar f' f'')
    (fs/delete f)
    (fs/delete f')
    f''))


(defn generate-db-namespace [locales zones]
  (let [f (slurp (io/resource "db.template"))]
    (->
      f
      (clojure.string/replace
        #"<<locales>>"
        (with-out-str 
          (clojure.pprint/pprint
            locales)))
      (clojure.string/replace  
        #"<<zones>>" 
        (with-out-str 
          (clojure.pprint/pprint
            zones))))))


(defn -main []
  (let [tz-dir (extract (download))]
    (try
      (binding [parser/*tz-dir* (str tz-dir "/")]
        (let [ld (parser/create-locale-data)
              zd (parser/create-timezone-data)] 
          (spit "src/core/vura/timezones/db.cljc"
                (generate-db-namespace ld zd))))
      (finally (fs/delete-dir tz-dir)))))


(comment
  (def x 
    (binding [parser/*tz-dir* (str tz-dir "/")]
      #_(parser/create-locale-data)
      (parser/create-timezone-data)))
  (binding [parser/*tz-dir* (str tz-dir "/")]
    ; (parser/extract-zones (parser/read-zone :europe))
    ; (parser/extract-rules (parser/read-zone :europe))
    (parser/extract-data (parser/read-zone :europe))
    ))
