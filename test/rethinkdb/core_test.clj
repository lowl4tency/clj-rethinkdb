(ns rethinkdb.core-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [rethinkdb.core :refer :all]
            [rethinkdb.query :as r]))

(def conn (connect))
(def test-db "cljrethinkdb_test")

(defmacro db-run [& body]
  (cons 'do (for [term body]
              `(-> (r/db test-db)
                   ~term
                   (r/run ~'conn)))))

(defn split-map [m]
  (map (fn [[k v]] {k v}) m))

(defn run [term]
  (r/run term conn))

(def pokemons [{:national_no 25
                :name "Pikachu"
                :type ["Electric"]}
               {:national_no 81
                :name "Magnemite"
                :type ["Electric" "Steel"]}])

(def bulbasaur {:national_no 1
                :name "Bulbasaur"
                :type ["Grass" "Poison"]})

(defn setup [test-fn]
  (if (some #{test-db} (r/run (r/db-list) conn))
    (r/run (r/db-drop test-db) conn))
  (r/run (r/db-create test-db) conn)
  (test-fn))

(defn between-notional-no [from to]
  (-> (r/db test-db)
      (r/table :pokedex)
      (r/between from to {:right-bound :closed})))

(defn with-name [name]
  (-> (r/db test-db)
      (r/table :pokedex)
      (r/filter (r/fn [row]
                  (r/eq (r/get-field row :name) name)))))

(deftest core-test
  (let [conn (connect)]
    (testing "manipulating databases"
      (is (= (r/run (r/db-create "cljrethinkdb_tmp") conn) {:created 1}))
      (is (= (r/run (r/db-drop "cljrethinkdb_tmp") conn) {:dropped 1}))
      (is (contains? (set (r/run (r/db-list) conn)) test-db)))

    (testing "manipulating tables"
      (db-run (r/table-create :tmp))
      (are [term result] (= (db-run term) result)
        (r/table-create :pokedex {:primary-key :national_no})        {:created 1}
        (r/table-drop :tmp) {:dropped 1}
        (-> (r/table :pokedex) (r/index-create :tmp (r/fn [row] 1))) {:created 1}
        (-> (r/table :pokedex)
            (r/index-create :type (r/fn [row]
                                    (r/get-field row :type))))       {:created 1}
        (-> (r/table :pokedex) (r/index-rename :tmp :xxx))           {:renamed 1}
        (-> (r/table :pokedex) (r/index-drop :xxx))                  {:dropped 1}
        (-> (r/table :pokedex) r/index-list)                         ["type"]))

    (testing "writing data"
      (are [term result] (contains? (set (split-map (db-run term))) result)
        (-> (r/table :pokedex) (r/insert bulbasaur))          {:inserted 1}
        (-> (r/table :pokedex) (r/insert pokemons))           {:inserted 2}
        (-> (r/table :pokedex)
            (r/get 1)
            (r/update {:japanese "Fushigidane"}))             {:replaced 1}
        (-> (r/table :pokedex)
            (r/get 1)
            (r/replace (merge bulbasaur {:weight "6.9 kg"}))) {:replaced 1}
        (-> (r/table :pokedex) (r/get 1) r/delete)            {:deleted 1}
        (-> (r/table :pokedex) r/sync)                        {:synced 1}))

    (testing "selecting data"
      (is (= (set (db-run (r/table :pokedex))) (set pokemons)))
      (is (= (db-run (-> (r/table :pokedex) (r/get 25))) (first pokemons)))
      (is (= (db-run (-> (r/table :pokedex) (r/get-all [25 81]))) pokemons))
      (is (= (run (between-notional-no 80 81)) [(last pokemons)]))
      (is (= (run (with-name "Pikachu")) [(first pokemons)])))

    (testing "string manipulating"
      (are [term result] (= (run term) result)
        (r/match "pikachu" "^pika")         {:str "pika" :start 0 :groups [] :end 4}
        (r/split "split this string")       ["split" "this" "string"]
        (r/split "split,this string" ",")   ["split" "this string"]
        (r/split "split this string" " " 1) ["split" "this string"]
        (r/upcase "Shouting")               "SHOUTING"
        (r/downcase "Whispering")           "whispering"))

    (testing "dates and times"
      (are [term result] (= (run term) result)
        (r/time 2014 12 31)          (t/date-time 2014 12 31)
        (r/time 2014 12 31 "+01:00") (t/date-time 2014 12 30 23)
        (r/time 2014 12 31 10 15 30) (t/date-time 2014 12 31 10 15 30)))

    (testing "control structure"
      (are [term result] (= (run term) result)
        (r/branch true 1 0)                         1
        (r/branch false 1 0)                        0
        (r/coerce-to [["name" "Pikachu"]] "OBJECT") {:name "Pikachu"}
        (r/type-of [1 2 3])                         "ARRAY"
        (r/type-of {:number 42})                    "OBJECT"
        (r/info (r/db test-db))                     {:type "DB" :name test-db}
        (r/json "{\"number\":42}") {:number 42}))

    (testing "math and logic"
      (are [term result] (= (run term) result)
        (r/add 2 2) 4))
    (close conn)))

(use-fixtures :once setup)
