;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.spec
  (:refer-clojure :exclude [+ * and or cat def keys resolve])
  (:require [cljs.analyzer.api :refer [resolve]]
            [clojure.walk :as walk]
            [cljs.spec.impl.gen :as gen]
            [clojure.string :as str]))

(defn- ->sym
  "Returns a symbol from a symbol or var"
  [x]
  (if (map? x)
    (:name x)
    x))

(defn- unfn [expr]
  (if (clojure.core/and (seq? expr)
             (symbol? (first expr))
             (= "fn*" (name (first expr))))
    (let [[[s] & form] (rest expr)]
      (conj (walk/postwalk-replace {s '%} form) '[%] 'fn))
    expr))

(defn- res [env form]
  (cond
    (keyword? form) form
    (symbol? form) (clojure.core/or (->> form (resolve env) ->sym) form)
    (sequential? form) (walk/postwalk #(if (symbol? %) (res env %) %) (unfn form))
    :else form))

(defmacro def
  "Given a namespace-qualified keyword or symbol k, and a spec, spec-name, predicate or regex-op
  makes an entry in the registry mapping k to the spec"
  [k spec-form]
  `(cljs.spec/def-impl ~k '~(res &env spec-form) ~spec-form))

(defmacro spec
  "Takes a single predicate form, e.g. can be the name of a predicate,
  like even?, or a fn literal like #(< % 42). Note that it is not
  generally necessary to wrap predicates in spec when using the rest
  of the spec macros, only to attach a unique generator

  Can also be passed the result of one of the regex ops -
  cat, alt, *, +, ?, in which case it will return a regex-conforming
  spec, useful when nesting an independent regex.
  ---

  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator.

  Returns a spec."
  [form & {:keys [gen]}]
  `(cljs.spec/spec-impl '~(res &env form) ~form ~gen nil))

(defmacro multi-spec
  "Takes the name of a spec/predicate-returning multimethod and a
  tag-restoring keyword or fn (retag).  Returns a spec that when
  conforming or explaining data will pass it to the multimethod to get
  an appropriate spec. You can e.g. use multi-spec to dynamically and
  extensibly associate specs with 'tagged' data (i.e. data where one
  of the fields indicates the shape of the rest of the structure).

  (defmulti mspec :tag)

  The methods should ignore their argument and return a predicate/spec:
  (defmethod mspec :int [_] (s/keys :req-un [::tag ::i]))

  retag is used during generation to retag generated values with
  matching tags. retag can either be a keyword, at which key the
  dispatch-tag will be assoc'ed, or a fn of generated value and
  dispatch-tag that should return an appropriately retagged value.

  Note that because the tags themselves comprise an open set,
  the tag key spec cannot enumerate the values, but can e.g.
  test for keyword?.

  Note also that the dispatch values of the multimethod will be
  included in the path, i.e. in reporting and gen overrides, even
  though those values are not evident in the spec.
"
  [mm retag]
  `(cljs.spec/multi-spec-impl '~(res &env mm) (var ~mm) ~retag))

(defmacro keys
  "Creates and returns a map validating spec. :req and :opt are both
  vectors of namespaced-qualified keywords. The validator will ensure
  the :req keys are present. The :opt keys serve as documentation and
  may be used by the generator.

  The :req key vector supports 'and' and 'or' for key groups:

  (s/keys :req [::x ::y (or ::secret (and ::user ::pwd))] :opt [::z])

  There are also -un versions of :req and :opt. These allow
  you to connect unqualified keys to specs.  In each case, fully
  qualfied keywords are passed, which name the specs, but unqualified
  keys (with the same name component) are expected and checked at
  conform-time, and generated during gen:

  (s/keys :req-un [:my.ns/x :my.ns/y])

  The above says keys :x and :y are required, and will be validated
  and generated by specs (if they exist) named :my.ns/x :my.ns/y
  respectively.

  In addition, the values of *all* namespace-qualified keys will be validated
  (and possibly destructured) by any registered specs. Note: there is
  no support for inline value specification, by design.

  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator."
  [& {:keys [req req-un opt opt-un gen]}]
  (let [unk #(-> % name keyword)
        req-keys (filterv keyword? (flatten req))
        req-un-specs (filterv keyword? (flatten req-un))
        _ (assert (every? #(clojure.core/and (keyword? %) (namespace %)) (concat req-keys req-un-specs opt opt-un))
                  "all keys must be namespace-qualified keywords")
        req-specs (into req-keys req-un-specs)
        req-keys (into req-keys (map unk req-un-specs))
        opt-keys (into (vec opt) (map unk opt-un))
        opt-specs (into (vec opt) opt-un)
        parse-req (fn [rk f]
                    (map (fn [x]
                           (if (keyword? x)
                             `#(contains? % ~(f x))
                             (let [gx (gensym)]
                               `(fn* [~gx]
                                  ~(walk/postwalk
                                     (fn [y] (if (keyword? y) `(contains? ~gx ~(f y)) y))
                                     x)))))
                         rk))
        pred-exprs [`map?]
        pred-exprs (into pred-exprs (parse-req req identity))
        pred-exprs (into pred-exprs (parse-req req-un unk))
        pred-forms (walk/postwalk #(res &env %) pred-exprs)]
    ;; `(map-spec-impl ~req-keys '~req ~opt '~pred-forms ~pred-exprs ~gen)
    `(cljs.spec/map-spec-impl {:req '~req :opt '~opt :req-un '~req-un :opt-un '~opt-un
                               :req-keys '~req-keys :req-specs '~req-specs
                               :opt-keys '~opt-keys :opt-specs '~opt-specs
                               :pred-forms '~pred-forms
                               :pred-exprs ~pred-exprs
                               :gfn ~gen})))

(defmacro or
  "Takes key+pred pairs, e.g.

  (s/or :even even? :small #(< % 42))

  Returns a destructuring spec that
  returns a vector containing the key of the first matching pred and the
  corresponding value."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv #(res &env %) pred-forms)]
    (assert (clojure.core/and (even? (count key-pred-forms)) (every? keyword? keys)) "spec/or expects k1 p1 k2 p2..., where ks are keywords")
    `(cljs.spec/or-spec-impl ~keys '~pf ~pred-forms nil)))

(defmacro and
  "Takes predicate/spec-forms, e.g.

  (s/and even? #(< % 42))

  Returns a spec that returns the conformed value. Successive
  conformed values propagate through rest of predicates."
  [& pred-forms]
  `(cljs.spec/and-spec-impl '~(mapv #(res &env %) pred-forms) ~(vec pred-forms) nil))

(defmacro *
  "Returns a regex op that matches zero or more values matching
  pred. Produces a vector of matches iff there is at least one match"
  [pred-form]
  `(cljs.spec/rep-impl '~(res &env pred-form) ~pred-form))

(defmacro +
  "Returns a regex op that matches one or more values matching
  pred. Produces a vector of matches"
  [pred-form]
  `(cljs.spec/rep+impl '~(res &env pred-form) ~pred-form))

(defmacro ?
  "Returns a regex op that matches zero or one value matching
  pred. Produces a single value (not a collection) if matched."
  [pred-form]
  `(cljs.spec/maybe-impl ~pred-form '~pred-form))

(defmacro alt
  "Takes key+pred pairs, e.g.

  (s/alt :even even? :small #(< % 42))

  Returns a regex op that returns a vector containing the key of the
  first matching pred and the corresponding value."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv #(res &env %) pred-forms)]
    (assert (clojure.core/and (even? (count key-pred-forms)) (every? keyword? keys)) "alt expects k1 p1 k2 p2..., where ks are keywords")
    `(cljs.spec/alt-impl ~keys ~pred-forms '~pf)))

(defmacro cat
  "Takes key+pred pairs, e.g.

  (s/cat :e even? :o odd?)

  Returns a regex op that matches (all) values in sequence, returning a map
  containing the keys of each pred and the corresponding value."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv #(res &env %) pred-forms)]
    ;;(prn key-pred-forms)
    (assert (clojure.core/and (even? (count key-pred-forms)) (every? keyword? keys)) "cat expects k1 p1 k2 p2..., where ks are keywords")
    `(cljs.spec/cat-impl ~keys ~pred-forms '~pf)))

(defmacro &
  "takes a regex op re, and predicates. Returns a regex-op that consumes
  input as per re but subjects the resulting value to the
  conjunction of the predicates, and any conforming they might perform."
  [re & preds]
  (let [pv (vec preds)]
    `(cljs.spec/amp-impl ~re ~pv '~pv)))

(defmacro conformer
  "takes a predicate function with the semantics of conform i.e. it should return either a
  (possibly converted) value or :clojure.spec/invalid, and returns a
  spec that uses it as a predicate/conformer"
  [f]
  `(cljs.spec/spec-impl '~f ~f nil true))

(defmacro fspec
  "takes :args :ret and (optional) :fn kwargs whose values are preds
  and returns a spec whose conform/explain take a fn and validates it
  using generative testing. The conformed value is always the fn itself.

  Optionally takes :gen generator-fn, which must be a fn of no args
  that returns a test.check generator."
  [& {:keys [args ret fn gen]}]
  (let [env &env]
    `(cljs.spec/fspec-impl ~args '~(res env args) ~ret '~(res env ret) ~fn '~(res env fn) ~gen)))

(defmacro tuple
  "takes one or more preds and returns a spec for a tuple, a vector
  where each element conforms to the corresponding pred. Each element
  will be referred to in paths using its ordinal."
  [& preds]
  (assert (not (empty? preds)))
  `(cljs.spec/tuple-impl '~(mapv #(res &env %) preds) ~(vec preds)))

(defn- ns-qualify
  "Qualify symbol s by resolving it or using the current *ns*."
  [env s]
  (if-let [resolved (resolve env s)]
    (->sym resolved)
    (if (namespace s)
      s
      (symbol (str (.name *ns*)) (str s)))))

(defn- fn-spec-sym
  [env sym role]
  (symbol (str (ns-qualify env sym) "$" (name role))))

(def ^:private _speced_vars (atom #{}))

(defn speced-vars*
  ([]
    (speced-vars* nil))
  ([ns-syms]
   (let [ns-match? (if (seq ns-syms)
                     (set ns-syms)
                     (constantly true))]
     (reduce
       (fn [ret sym]
         (if (ns-match? (namespace sym))
           (conj ret (list 'var sym))
           ret))
       #{} @_speced_vars))))

(defmacro speced-vars
  "Returns the set of vars whose namespace is in ns-syms AND
whose vars have been speced with fdef. If no ns-syms are
specified, return speced vars from all namespaces."
  [& ns-syms]
  (speced-vars* ns-syms))

(defmacro fdef
  "Takes a symbol naming a function, and one or more of the following:

  :args A regex spec for the function arguments as they were a list to be
    passed to apply - in this way, a single spec can handle functions with
    multiple arities
  :ret A spec for the function's return value
  :fn A spec of the relationship between args and ret - the
    value passed is {:args conformed-args :ret conformed-ret} and is
    expected to contain predicates that relate those values

  Qualifies fn-sym with resolve, or using *ns* if no resolution found.
  Registers specs in the global registry, where they can be retrieved
  by calling fn-specs.

  Once registered, function specs are included in doc, checked by
  instrument, tested by the runner clojure.spec.test/run-tests, and (if
  a macro) used to explain errors during macroexpansion.

  Note that :fn specs require the presence of :args and :ret specs to
  conform values, and so :fn specs will be ignored if :args or :ret
  are missing.

  Returns the qualified fn-sym.

  For example, to register function specs for the symbol function:

  (s/fdef clojure.core/symbol
    :args (s/alt :separate (s/cat :ns string? :n string?)
                 :str string?
                 :sym symbol?)
    :ret symbol?)"
  [fn-sym & {:keys [args ret fn] :as m}]
  (swap! _speced_vars conj (:name (resolve &env fn-sym)))
  (let [env &env
        qn  (ns-qualify env fn-sym)]
    `(do ~@(reduce
             (clojure.core/fn [defns role]
               (if (contains? m role)
                 (let [s (fn-spec-sym env qn (name role))]
                   (conj defns `(cljs.spec/def '~s ~(get m role))))
                 defns))
             [] [:args :ret :fn])
         '~qn)))

(defmacro with-instrument-disabled
  "Disables instrument's checking of calls, within a scope."
  [& body]
  `(binding [*instrument-enabled* nil]
     ~@body))

(defmacro keys*
  "takes the same arguments as spec/keys and returns a regex op that matches sequences of key/values,
  converts them into a map, and conforms that map with a corresponding
  spec/keys call:

  user=> (s/conform (s/keys :req-un [::a ::c]) {:a 1 :c 2})
  {:a 1, :c 2}
  user=> (s/conform (s/keys* :req-un [::a ::c]) [:a 1 :c 2])
  {:a 1, :c 2}

  the resulting regex op can be composed into a larger regex:

  user=> (s/conform (s/cat :i1 integer? :m (s/keys* :req-un [::a ::c]) :i2 integer?) [42 :a 1 :c 2 :d 4 99])
  {:i1 42, :m {:a 1, :c 2, :d 4}, :i2 99}"
  [& kspecs]
  `(& (* (cat ::k keyword? ::v ::any)) ::kvs->map (keys ~@kspecs)))

(defmacro nilable
  "returns a spec that accepts nil and values satisfiying pred"
  [pred]
  `(and (or ::nil nil? ::pred ~pred) (conformer second)))

(defmacro coll-of
  "Returns a spec for a collection of items satisfying pred. The generator will fill an empty init-coll."
  [pred init-coll]
  `(spec (cljs.spec/coll-checker ~pred) :gen (cljs.spec/coll-gen ~pred ~init-coll)))

(defmacro map-of
  "Returns a spec for a map whose keys satisfy kpred and vals satisfy vpred."
  [kpred vpred]
  `(and (coll-of (tuple ~kpred ~vpred) {}) map?))

(defmacro instrument
  "Instruments the var at v, a var or symbol, to check specs
  registered with fdef. Wraps the fn at v to check :args/:ret/:fn
  specs, if they exist, throwing an ex-info with explain-data if a
  check fails. Idempotent."
  [v]
  (let [v   (if-not (seq? v) (list 'var v) v)
        sym (second v)]
    `(when-let [checked# (cljs.spec/instrument* ~v)]
       (set! ~sym checked#)
       ~v)))

(defmacro unstrument
  "Undoes instrument on the var at v, a var or symbol. Idempotent."[v]
  (let [v   (if-not (seq? v) (list 'var v) v)
        sym (second v)]
    `(when-let [raw# (cljs.spec/unstrument* ~v)]
       (set! ~sym raw#)
       ~v)))

(defmacro instrument-ns
  "Call instrument for all speced-vars in namespaces named
by ns-syms. Idempotent."
  [& ns-syms]
  `(do
     ~@(map #(list 'cljs.spec/instrument %) (speced-vars* ns-syms))))

(defmacro unstrument-ns
  "Call unstrument for all speced-vars in namespaces named
by ns-syms. Idempotent."
  [& ns-syms]
  `(do
     ~@(map #(list 'cljs.spec/unstrument %) (speced-vars* ns-syms))))

(defmacro instrument-all
  "Call instrument for all speced-vars. Idempotent."
  []
  `(do
     ~@(map #(list 'cljs.spec/instrument %) (speced-vars*))))

(defmacro unstrument-all
  "Call unstrument for all speced-vars. Idempotent"
  []
  `(do
     ~@(map #(list 'cljs.spec/unstrument %) (speced-vars*))))
