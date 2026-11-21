# Assumptions

> The scenario is intentionally incomplete. Record every assumption you needed to make, and the part it affects.

| # | Assumption | Why I needed it | Part(s) affected |
|---|---|---|---|
| A-1 | The API Manager forwards the request body to the backend **byte for byte** (no re-serialisation, no charset conversion) and passes the provider signature headers through. | HMAC signatures are computed over the raw body; any transformation breaks verification. | A, B |
| A-2 | The API Manager can reach Azure Container Apps over private connectivity (or a fixed egress IP that can be allow-listed), so the services are not directly reachable from the internet. | Trust boundary between the edge and Azure. | A, C |
| A-3 | Providers publish their egress IP ranges; if they do not, the IP allow-list is skipped and the HMAC signature is the only authentication. | Defence in depth at the edge. | A |
| A-4 | A transaction cannot move between `CAPTURED` and `FAILED` (a retried payment gets a new transaction/charge id). | Precedence rule ranks both at the same level. | A, B |
| A-5 | A Quetzal partial refund is mapped to `Refunded` with `refundedAmount`, because `Premium_Payment__c.Status__c` has no partial-refund value. The Salesforce team must confirm how the flow should recalculate `Overdue_Amount__c` for partial refunds. | Data model gap. | A |
| A-6 | A purely numeric Andina reference (1–9 digits) is the numeric part of the policy number, so it is normalised by padding to 9 digits and adding `POL-CO-`. Anything else is `UNRESOLVED` and goes to operations. | Andina 2.3 sends the reference as typed by the payer. | A, B, D |
| A-7 | Finance and Notifications do not need payer personal data in the event; they can resolve the customer from the policy in Salesforce. | Data minimisation (NFR6). | A, B |
| A-8 | Retention of 13 months for the notification log satisfies audit and chargeback needs; legal will confirm per country. No personal data is stored, so privacy laws do not shorten it. | No retention policy is given. | A, C |
| A-9 | The Salesforce record-triggered flow is bulk-safe for 200 records per transaction (composite upsert). | Batching relies on it. | A |
| A-10 | Clocks of Meridian and providers are NTP-synchronised; the timestamp tolerances in the specs (300 s / 600 s) are applied as given. | Replay protection. | B |
| A-11 | One Service Bus namespace and one PostgreSQL server serve the three countries; country is a dimension of data and telemetry, not a separate deployment. One region serves the three countries (as stated). | Cost and simplicity; volumes are small. | A, C |
| A-12 | The raw payment reference (`rawReference`, max 100 characters) is kept in the event for diagnosis. Since Andina 2.3 it is free text typed by the payer, so it could occasionally contain personal data (e.g. a national ID typed by mistake). Accepted risk, to be confirmed with the privacy team; if they disagree, the field is kept only in the processor's store and the ops API, and removed from the topic event through a major schema version (see `docs/api/README.md`). | Diagnosing unresolved references (INC-0412) needs the original value. | A, B |
| A-13 | "Java 21 (Spring Boot 3 …)" in the README describes the accepted Java stack, not a hard version constraint. Spring Boot 4 is used because Spring Boot 3 carries a critical, fixable Spring MVC vulnerability with no 3.x patch (ADR-0002). | The CI vulnerability gate would otherwise fail on a known critical CVE. | B, C |
| A-14 | The order-of-magnitude cost estimate uses pay-as-you-go list prices of a US region; the actual region serving MX, CO and CL, enterprise agreements and reservations will change the absolute figures, not the relative weight of each service. | No region or pricing agreement is given. | C |
