(ns om-next-ideas.remote-parser
  (:require
    [clojure.pprint :refer [pprint]]
    [om-next-ideas.remote-core :refer [readf mutate Id OmIdent]]
    [om.next.server :as om]
    [com.stuartsierra.component :as component]
    [schema.core :as s]
    [datomic.api :as d]
    [taoensso.timbre :as log]))

(s/defschema EngineQuery [(s/enum :db/id :engine/torque :engine/hp)])

(s/defschema CarQuery [(s/conditional
                         keyword? (s/enum :db/id :car/name)
                         map? {:car/engine EngineQuery})])

(s/defschema PersonQuery [(s/conditional
                            keyword? (s/enum :db/id :person/name)
                            map? {:person/cars CarQuery})])


; read fns

(s/defmethod readf :people
             [{:keys [db query]} _ params]
             (s/validate PersonQuery query)
             (let [q '[:find [(pull ?p selector) ...]
                       :in $ selector
                       :where
                       [?p :person/name]]]
               (log/trace {:query query
                           :q     q})
               (->> query
                    (d/q q db)
                    (hash-map :value))))

(s/defmethod readf :cars
             [{:keys [db query]} _ params]
             (s/validate CarQuery query)
             (let [q '[:find [(pull ?p selector) ...]
                       :in $ selector
                       :where
                       [?p :car/name]]]
               (log/trace {:query query
                           :q     q})
               (->> query
                    (d/q q db)
                    (hash-map :value))))

; mutations

(s/defschema MutationEnv {:db         s/Any
                          :connection s/Any
                          s/Keyword   s/Any})

(s/defn map-type :- (s/enum :person :car)
  "determine the entity type from a maps keys"
  [m]
  (->> (keys m)
       (some #{:person/name :car/name})
       namespace
       keyword))

(s/defn type= [t] (fn [m] (= t (map-type m))))

(s/defschema SyncableMap (s/conditional
                           (type= :person) {:db/id                        Id
                                            :person/name                  s/Str
                                            (s/optional-key :person/cars) [Id]}
                           (type= :car) {:db/id    Id
                                         :car/name s/Str}))

(s/defmethod ^:always-validate mutate 'app/sync
             [{:keys [db connection]} :- MutationEnv
              _
              {:keys [db/id] :as params} :- SyncableMap]
             {:action (fn []
                        (let [is-update? (number? id)
                              db-id (if is-update? id (d/tempid :db.part/user))
                              entity-keys (case (map-type params)
                                            :person [:person/name :person/cars]
                                            :car [:car/name])
                              p (assoc
                                  (select-keys params entity-keys)
                                  :db/id db-id)
                              {:keys [db-after tempids]} @(d/transact connection [p])]
                          (when (not is-update?)
                            {:tempids {id (d/resolve-tempid db-after tempids db-id)}})))})

; Component and API Protocol

(defprotocol Parser
  (parse [this request]))

(defrecord ParserImpl [parser]
  Parser
  (parse [c request]
    (parser (assoc c
              ; add the database value at the start of the request as a convenience for read fns
              :db (d/db (get-in c [:datomic :connection]))
              :connection (get-in c [:datomic :connection]))
            request))

  component/Lifecycle
  (start [component]
    (assoc component :parser parser))
  (stop [component]
    (dissoc component :parser)))

(defn new-api []
  (component/using
    ; TODO use a wrapper here to log exceptions and return an error result to client without the stack trace
    (->ParserImpl (om/parser {:read readf :mutate mutate}))
    [:datomic]))