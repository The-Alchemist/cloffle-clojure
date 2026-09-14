(ns bench.snippet.kwargs-destructure)

(defn bench []
    (let [opts {:method :post :timeout "500ms"}
        {:keys [method timeout] :or {method :get timeout "1000ms"}} opts]
    (if (= method :post) timeout "none")))
