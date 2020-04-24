# THIS REPO ISN'T MAINTANIED ANYMORE you can find re-frisk-remote here https://github.com/flexsurfer/re-frisk


Simple debugging tool for React Native re-frame apps. **shadow-cljs compatible**.

[![Clojars](https://img.shields.io/clojars/v/re-frisk-rn.svg)](https://clojars.org/re-frisk-rn)

## Usage

Add `[re-frisk-rn "0.1.1"]` dependency to your project.

Enable re-frisk in your RN app:

```clojure
:devtools   {...           
             :preloads [re-frisk-rn.preload]}
```

OR

```clojure
(:require [re-frisk-rn.core :as rfr])

(defn init []
  (rfr/enable {:on-init #(rn/register-nav-comp "yourapp" root-stack)}))
```


Run re-frisk in terminal

` shadow-cljs run re-frisk-rn.core/start`

Build and run your app

If you see websocket error add `yarn add websocket` package

Open re-frisk in browser at http://localhost:4567

When remote debugging on an Android device you might need to enable reverse socket connections on port 4567:

```bash
adb reverse tcp:4567 tcp:4567
```

ENJOY!

