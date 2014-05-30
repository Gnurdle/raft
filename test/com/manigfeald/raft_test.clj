(ns com.manigfeald.raft-test
  (:require [clojure.test :refer :all]
            [com.manigfeald.raft :refer :all]
            [clojure.core.async :refer [alt!! timeout <!! >!! chan
                                        sliding-buffer dropping-buffer
                                        close!]]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pp]
            [robert.bruce :refer [try-try-again]]))

;; stop sf4j or whatever from printing out nonsense when you run tests
(log/info "logging is terrible")

(defprotocol Cluster
  (add-node [cluster node arg1])
  (remove-node [cluster node])
  (list-nodes [cluster])
  (send-to [cluster node msg]))

(defrecord ChannelCluster []
  Cluster
  (add-node [cluster node arg1]
    (assoc cluster node arg1))
  (remove-node [cluster node]
    (dissoc cluster node))
  (list-nodes [cluster]
    (keys cluster))
  (send-to [cluster node msg]
    (assert (contains? cluster node) [node msg])
    (>!! (get cluster node) msg)))

(defn shut-it-down! [nodes]
  (doseq [i nodes]
    (future-cancel (:future i))))

;; ;; (deftest test-read-operations
;; ;;   (let [leader (chan (dropping-buffer 2))
;; ;;         [leaders nodes commited value] (f 3 leader)]
;; ;;     (try
;; ;;       (testing "elect leader"
;; ;;         (is (stable-leader? nodes 3)))
;; ;;       (let [leader (first (vals @leaders))
;; ;;             c (chan 1)]
;; ;;         (doseq [node nodes
;; ;;                 :when (= leader (:id node))]
;; ;;           (>!! (:in node) {:type :operation
;; ;;                            :op :write
;; ;;                            :key "hello"
;; ;;                            :value "world"
;; ;;                            :operation-type :write
;; ;;                            :serial 1})
;; ;;           (>!! (:in node) {:type :operation
;; ;;                            :op :write
;; ;;                            :key "hello"
;; ;;                            :value "bob"
;; ;;                            :operation-type :write
;; ;;                            :serial 2})
;; ;;           (>!! (:in node) {:type :operation
;; ;;                            :op :read
;; ;;                            :key "hello"
;; ;;                            :operation-type :read
;; ;;                            :serial 3})
;; ;;           (>!! (:in node) {:type :await
;; ;;                            :callback (fn [{:keys [value]}]
;; ;;                                        (>!! c value))
;; ;;                            :serial 3}))
;; ;;         (is (= (v c (* 1000 60) :timeout) "bob")))
;; ;;       (finally
;; ;;         (shut-it-down! nodes)))))

;; ;; (deftest test-kill-node
;; ;;   (dotimes [i 8]
;; ;;     (let [n (inc (* (inc i) 2))
;; ;;           leader (chan (dropping-buffer 10))
;; ;;           [leaders nodes] (f n leader)]
;; ;;       (try
;; ;;         (testing "elect leader"
;; ;;           (is (stable-leader? nodes n)))
;; ;;         (dotimes [ii (Math/floor (/ n 2))]
;; ;;           (testing "kill leader and elect a new one"
;; ;;             (let [[leader'] (leaders-of nodes 1)]
;; ;;               (doseq [node nodes
;; ;;                       :when (= leader' (:id node))]
;; ;;                 (future-cancel (:future node)))
;; ;;               (is (stable-leader? (for [node nodes
;; ;;                                         :when (not (future-done? (:future node)))]
;; ;;                                     node)
;; ;;                                   (- n (inc ii)))))))
;; ;;         (finally
;; ;;           (shut-it-down! nodes))))))

(defn raft-obj [in id cluster]
  (let [s (atom nil)
        lock (java.util.concurrent.Semaphore. 1)
        commands (chan)
        f (future
            (try
              (loop [state (raft id (conj (set (list-nodes cluster)) id)
                                 (->Timer (System/currentTimeMillis)
                                          (System/currentTimeMillis)
                                          1500))]
                (let [state (cond-> state
                                    (= :leader (:node-type (:raft-state state)))
                                    (assoc-in [:timer :period] 300)
                                    (not= :leader (:node-type (:raft-state state)))
                                    (assoc-in [:timer :period]
                                              (+ 500 (rand-int 1000))))
                      message (alt!!
                               commands
                               ([message] message)
                               in
                               ([message] message)
                               ;; (timeout 100) ([_] nil)
                               :default nil
                               :priority true)]
                  (let [_ (.acquire lock)
                        new-state (try
                                    (-> state
                                        (assoc-in [:timer :now]
                                                  (System/currentTimeMillis))
                                        (assoc-in [:io :message] message)
                                        (run-one))
                                    (finally
                                      (.release lock)))
                        ;; _ (when (= :operation (:type message))
                        ;;     (log/trace (:applied-rules new-state)))
                        _ (doseq [msg (:out-queue (:io new-state))]
                            (assert (not= :broadcast (:target msg)))
                            (send-to cluster (:target msg) msg))
                        _ (doseq [{:keys [level message] :as m}
                                  (:running-log new-state)]
                            (case level
                              :trace (log/trace message)))
                        new-state (update-in new-state [:io :out-queue] empty)
                        new-state (update-in new-state [:running-log] empty)
                        new-state (update-in new-state [:applied-rules] empty)]
                    (reset! s new-state)
                    (if (:stopped? new-state)
                      new-state
                      (recur new-state)))))
              (catch Throwable t
                (log/error t "whoops"))))]
    {:in in
     :id id
     :cluster cluster
     :raft s
     :lock lock
     :commands commands
     :future f}))


(defn stable-leader? [nodes n]
  (loop [i 100]
    (Thread/sleep 1000)
    (if (zero? i)
      false
      (let [lead (for [node nodes
                       :let [{:keys [raft]} node
                             v (deref raft)
                             {{:keys [leader-id]} :raft-state} v]
                       :when leader-id
                       leader nodes
                       :when (= leader-id (:id leader))]
                   leader)
            f (frequencies lead)
            [lead c] (last (sort-by second f))]
        (log/trace "stable-leader?" n (into {} (for [[a b] f]
                                                 [(-> a :raft deref :id) b])))
        (if (and lead
                 (>= c n))
          lead
          (recur (dec i)))))))

(defn await-applied [nodes serial-w dunno]
  (try
    (try-try-again
     {:decay :exponential
      :sleep 100
      :tries 6}
     (fn []
       (log/trace "await-applied body")
       (let [r (for [node nodes
                     :let [{:keys [raft]} node v (deref raft)
                           {{:keys [last-applied log]} :raft-state} v]
                     [_ {:keys [index serial return]}] log
                     :when (= serial serial-w)
                     :when (>= last-applied index)]
                 return)]
         (if (= (count nodes) (count r))
           (first r)
           (throw (Exception.))))))
    (catch Exception _
      dunno)))

;; TODO: may not be required
(defn await-n-applied [nodes n]
  (let [leader (stable-leader? nodes 1)
        leader-commit (-> leader :raft deref :raft-state :commit-index)]
    (loop [i 100]
      (Thread/sleep 1000)
      (if (zero? i)
        false
        (let [lead (for [node nodes
                         :let [{:keys [raft]} node
                               v (deref raft)
                               {{:keys [commit-index]} :raft-state} v]
                         :when (>= commit-index leader-commit)]
                     commit-index)]
          (log/trace "await-n-applied" n leader-commit)
          (if (>= (count lead) n)
            lead
            (recur (dec i))))))))

(defn raft-write [nodes key value]
  (let [id (java.util.UUID/randomUUID)]
    (try
      (try-try-again
       {:sleep 10
        :tries 100}
       (fn []
         (let [leader (stable-leader? nodes 1)]
           (log/trace "raft-write" key value (:id leader))
           (>!! (:commands leader) {:type :operation
                                    :payload {:op :write
                                              :key key
                                              :value value}
                                    :operation-type ::bogon
                                    :serial id})
           (when (= :dunno (await-applied nodes id :dunno))
             (throw (Exception. "write failed?"))))))
      (catch Exception e
        (doseq [node nodes]
          (log/trace (-> node :raft deref)))
        (throw e)))))

;; (defn raft-read [nodes key]
;;   (loop [id (java.util.UUID/randomUUID)
;;          leader (stable-leader?* nodes 1)]
;;     (log/trace "raft-read")
;;     (if (not leader)
;;       (recur nodes key)
;;       (do
;;         (>!! (:in leader) {:type :operation
;;                            :op :read
;;                            :key key
;;                            :operation-type :read
;;                            :serial id})
;;         (let [r (await-applied nodes id 10 :dunno)]
;;           (if (= r :dunno)
;;             (recur id (stable-leader?* nodes 1))
;;             r))))))

(deftest test-leader-election
  (let [node-ids-and-channels (into {} (for [i (range 3)]
                                         [i (chan (sliding-buffer 10))]))
        cluster (reduce #(add-node % (key %2) (val %2)) (->ChannelCluster) node-ids-and-channels)
        nodes (doall (for [[node-id in] node-ids-and-channels]
                       (raft-obj in node-id cluster)))]
    (try
      (is (stable-leader? nodes 3))
      (finally
        (shut-it-down! nodes)))))

(deftest test-remove-node
  (let [node-ids-and-channels (into {} (for [i (range 5)]
                                         [i (chan (sliding-buffer 10))]))
        cluster (reduce #(add-node % (key %2) (val %2)) (->ChannelCluster) node-ids-and-channels)
        nodes (doall (for [[node-id in] node-ids-and-channels]
                       (raft-obj in node-id cluster)))]
    (try
      (testing "elect leader"
        (is (stable-leader? nodes 5)))
      (testing "kill leader and elect a new one"
        (future-cancel (:future (stable-leader? nodes 5)))
        (is (stable-leader? nodes 4))
        (future-cancel (:future (stable-leader? nodes 4)))
        (is (stable-leader? nodes 3)))
      (finally
        (shut-it-down! nodes)))))

(deftest test-operations
  (let [node-ids-and-channels (into {} (for [i (range 5)]
                                         [i (chan (sliding-buffer 10))]))
        cluster (reduce #(add-node % (key %2) (val %2)) (->ChannelCluster) node-ids-and-channels)
        nodes (doall (for [[node-id in] node-ids-and-channels]
                       (raft-obj in node-id cluster)))]
    (try
      (testing "elect leader"
        (is (stable-leader? nodes 5)))
      (raft-write nodes "hello" "world")
      (await-n-applied nodes 5)
      (doseq [node nodes
              :let [{:keys [raft]} node
                    {{{:strs [hello]} :value} :raft-state} (deref raft)]]
        (is (= hello "world") (deref (:raft (stable-leader? nodes 5)))))
      ;; (doseq [node nodes
      ;;         :let [{:keys [raft]} node
      ;;               x (deref raft)]]
      ;;   (println)
      ;;   (prn x))
      (finally
        (shut-it-down! nodes)))))

;; (deftest test-stress
;;   (let [node-ids-and-channels (into {} (for [i (range 3)]
;;                                          [i (chan (sliding-buffer 10))]))
;;         cluster (reduce #(add-node % (key %2) (val %2)) (->ChannelCluster) node-ids-and-channels)
;;         nodes (doall (for [[node-id in] node-ids-and-channels]
;;                        (raft-obj in node-id cluster)))]
;;     (try
;;       (is (stable-leader?* nodes 3))
;;       (raft-write nodes :key 0)
;;       (dotimes [i 10]
;;         (log/trace "writing" i)
;;         (let [leader (stable-leader?* nodes 1)
;;               victim (rand-nth nodes)
;;               write-id (java.util.UUID/randomUUID)
;;               read-id (java.util.UUID/randomUUID)]
;;           (.acquire (:lock victim))
;;           (try
;;             (let [ii (raft-read nodes :key)]
;;               (raft-write nodes :key (inc ii))
;;               (is (= (inc ii) (raft-read nodes :key)))
;;               (is (= i ii)))
;;             (finally
;;               (.release (:lock victim))))
;;           (raft-read nodes :key)))
;;       (finally
;;         (shut-it-down! nodes)))))

(defmacro foo [exp & body]
  (when-not (eval exp)
    `(do ~@body)))

(foo (resolve 'clojure.test/original-test-var)
     (in-ns 'clojure.test)

     (def original-test-var test-var)

     (defn test-var [v]
       (clojure.tools.logging/trace "testing" v)
       (let [start (System/currentTimeMillis)
             result (original-test-var v)]
         (clojure.tools.logging/trace
          "testing" v "took"
          (/ (- (System/currentTimeMillis) start) 1000.0)
          "seconds")
         result))

     (def original-test-ns test-ns)

     (defn test-ns [ns]
       (clojure.tools.logging/trace "testing namespace" ns)
       (let [start (System/currentTimeMillis)
             result (original-test-ns ns)]
         (clojure.tools.logging/trace
          "testing namespace" ns "took"
          (/ (- (System/currentTimeMillis) start) 1000.0)
          "seconds")
         result))

     (in-ns 'com.manigfeald.raft-test))
