(ns bench.snippet.cross-call-validation-pipeline-threaded)

(defn make-request []
  {:request-id "req-202"
   :operation :create
   :tenant-id "org-3"
   :headers {:accept "application/fhir+json"
             :content-type "application/json"}
   :actor {:id "user-7"
           :role :clinician
           :scopes [:patient/read :patient/write :audit/read :tenant/admin]}
   :resource {:resource-type :patient
              :id "patient-101"
              :profile {:active false :locale "en-US"}
              :identifiers [{:system "urn:mrn" :value "mrn-101"}
                            {:system "urn:ssn" :value "ssn-202"}]}
   :body "validated-payload"})

(defn normalize-request [req]
  (let [headers (assoc (:headers req) :content-type "application/fhir+json")
        resource (:resource req)
        profile (assoc (:profile resource) :active true)]
    (-> req
        (assoc :headers headers)
        (assoc :resource (assoc resource :profile profile)))))

(defn validate-request [req]
  (let [actor (:actor req)
        [scope0 scope1 scope2 scope3] (:scopes actor)
        resource (:resource req)
        [mrn ssn] (:identifiers resource)
        actor-valid (and (= (:role actor) :clinician)
                         (= scope0 :patient/read)
                         (= scope1 :patient/write)
                         (= scope2 :audit/read)
                         (= scope3 :tenant/admin))
        resource-valid (and (= (:resource-type resource) :patient)
                            (= (:active (:profile resource)) true)
                            (= (:system mrn) "urn:mrn")
                            (= (:system ssn) "urn:ssn"))
        headers-valid (= (:content-type (:headers req))
                         "application/fhir+json")]
    (assoc req :validation {:actor-valid actor-valid
                            :resource-valid resource-valid
                            :headers-valid headers-valid})))

(defn authorize-request [req]
  (let [validation (:validation req)
        authorized (and (= (:actor-valid validation) true)
                        (= (:resource-valid validation) true)
                        (= (:headers-valid validation) true))]
    (assoc req :validation (assoc validation :authorized authorized))))

(defn consume-request [req]
  (let [validation (:validation req)]
    (if (and (= (:authorized validation) true)
             (= (:operation req) :create)
             (= (:tenant-id req) "org-3")
             (= (:request-id req) "req-202"))
      (:body req)
      nil)))

(defn bench []
  (-> (make-request)
      normalize-request
      validate-request
      authorize-request
      consume-request))
