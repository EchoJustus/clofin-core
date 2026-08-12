(ns clofin.tools.mermaid
  "Mermaid emission — the drawing half of RULE 1.

  Mermaid rather than SVG for one reason that matters and one that follows from
  it. The one that matters: rendering Mermaid to SVG needs Node, and
  [ADR-0004](../../../docs/ADR/0004-minimal-dependency-footprint.md) and NFR-007
  say no. The one that follows: a Mermaid diagram is text, so a drift shows up
  in `git diff` as the arrow that moved rather than as a wall of changed path
  coordinates, which is what makes `make diagrams-check`'s output worth reading.

  Everything here emits **sorted, byte-stable** text. Nothing generates an
  identifier from a hash, a counter shared across runs, or map iteration order."
  (:require [clojure.string :as str]))

(def ^:private label-escapes
  "Characters that end a Mermaid label early, and their entity codes.

  `#` is escaped **first** and is therefore listed first — the remaining
  replacements introduce `#` themselves, and escaping it afterwards would
  mangle them."
  [["#" "#35;"]
   ["\"" "#quot;"]
   ["<" "#lt;"]
   [">" "#gt;"]])

(defn- escape
  [s]
  (reduce (fn [acc [from to]] (str/replace acc from to)) s label-escapes))

(defn wrap
  "`text` broken into lines of at most `width` characters, on word boundaries.

  A word longer than `width` gets its own line rather than being cut: a
  truncated identifier in a control map is worse than a wide box, because a
  reader cannot tell it was truncated."
  [text width]
  (let [words (remove str/blank? (str/split (str/trim text) #"\s+"))]
    (->> words
         (reduce (fn [acc word]
                   (let [line (peek acc)]
                     (if (and line (<= (+ (count line) 1 (count word)) width))
                       (conj (pop acc) (str line " " word))
                       (conj acc word))))
                 [])
         vec)))

(defn label
  "`text` as a Mermaid node label: escaped, wrapped, and quoted.

  A newline in `text` is a **hard** break the caller asked for — a context box
  puts its namespace root on its own line — and survives wrapping. Everything
  else is wrapped at `width`.

  The quotes are part of the return value because an unquoted Mermaid label
  cannot contain a space, and every label here can."
  ([text] (label text 44))
  ([text width]
   (str "\""
        (->> (str/split (str text) #"\n" -1)
             (mapcat #(wrap % width))
             (map escape)
             (str/join "<br/>"))
        "\"")))

(defn identifier
  "`s` reduced to a Mermaid-safe node id.

  Deterministic and injective enough for this repository's vocabularies, which
  are namespace roots, control ids and payment statuses. Callers that cannot
  guarantee injectivity — the control map's enforcement points, whose text is
  arbitrary prose — number their nodes by sorted position instead."
  [s]
  (let [cleaned (-> (str/lower-case (str s))
                    (str/replace #"[^a-z0-9]+" "_")
                    (str/replace #"^_+|_+$" ""))]
    (if (str/blank? cleaned) "n" (str (when (Character/isDigit (first cleaned)) "n") cleaned))))

(defn block
  "`lines` as a fenced Mermaid block, ready to embed in Markdown."
  [lines]
  (str "```mermaid\n" (str/join "\n" (remove nil? lines)) "\n```"))
