

# Zookeeper Atom

Looks and behaves like a [Clojure atom](http://clojure.org/atoms) but works distributed across many machines by storing it's data as EDN in a [Zookeeper](http://zookeeper.apache.org/) node.

## Features

 - Simple API: `atom` (to create), `deref`/`@`, `swap`, and `reset`.  Just like Clojure's API.
 - Reads don't block, they just return a cached value. All updates from Zookeeper get applied in the background.
 - Values are encoded as EDN.


## Usage

This example requires that you have Zookeeper installed and running:

```clojure
(require '[zookeeper-atom :as zk])

(def client (zk/connect "127.0.0.1"))
(def a-atom (zk/atom client "/some/znode/path"))

@a-atom
;; => nil

(zk/swap a-atom assoc :foo "bar")
;; => {:foo "bar"}

(zk/reset a-atom {})
;; => {}
```


## Contributing

Reporting bugs and pull requests are very welcome!

Here is a little snippet to get you started in your REPL in case you want to extend zookeeper-atom:

```clojure
(use 'clj-logging-config.log4j 'midje.repl)
(set-loggers! :root {:level :warn} "zookeeper-atom" {:level :debug})
(autotest)
; hack!
```


## License

Copyright © 2014 Torsten Becker.

Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
