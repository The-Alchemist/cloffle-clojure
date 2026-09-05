(let [opts {:method :post :timeout 500}
      {:keys [method timeout] :or {method :get timeout 1000}} opts]
  (if (= method :post) timeout 0))
