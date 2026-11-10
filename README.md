# Document routing — a Temporal demo

A document arrives, is classified, and business-unit rules route it down one of three paths:

- **`HumanReview`.** A person finishes the request. The first task goes to the business unit's first assignee from the rules (by default, Alice for Services and Bob for Equipment); whoever holds it can complete it, which notifies the requester and ends the request, or reassign it to anyone by name. Each assignment has its own SLA.
- **`Invoice`.** The invoice is posted to the ERP, which confirms the posting asynchronously, and the requester is notified.
- **`PurchaseOrder`.** Budget is reserved against the purchase order's cost center, which fails without retrying if the cost center is unknown, and the PO is sent to the supplier portal. If the supplier rejects it, the budget reservation is released.

Each document is one Temporal Workflow. You can stop the worker, take the external systems down, change routing rules live, and ship a new worker version while requests are in flight, and nothing is lost or done twice.

The [RUNBOOK](RUNBOOK.md) is the presenter's script.

**Built with:** Java 21 · Temporal Java SDK 1.40.0 · Javalin · vanilla JS. Runs locally only.

## Prerequisites

- JDK 21 or later on your `PATH`. The Makefile points Maven at that JDK even if `JAVA_HOME` is older.
- Maven 3.9 or later.
- [Temporal CLI](https://docs.temporal.io/cli) 1.9 or later, for Worker Deployments on the dev server. Tested with CLI 1.9.1 (Server 1.32.0, UI 2.54.1).

You need no Temporal Cloud account, API key, or Docker.

## Run it

In two terminals, from this directory:

```sh
make up         # Temporal dev server, mock systems and the demo app
make worker     # the Temporal worker (version v1)
```

`make up` runs three processes in one terminal, and `Ctrl-C` stops all three:

- the Temporal dev server, with its UI at http://localhost:8233;
- the mock ERP, budget service, supplier portal and notifications on :8090;
- the demo app at http://localhost:8080.

The worker runs on its own because the demo stops and restarts it. To run the other three in separate terminals instead, use `make temporal`, `make systems` and `make web`.

The first `make up` or `make worker` copies the default rules and reference data into `data/`. Those copies are what changes live during the demo. The page's **Routing rules** card edits each unit's threshold and first assignee in `data/rules.json`, and you can edit the file by hand too. **Reset demo data** on the page, or `make reset-data`, restores the defaults; the page button also clears the system fault toggles. The rules hold only those two settings per unit. Above its unit's threshold, a document goes to a person, starting with the unit's first assignee. Below it, a document's path follows from its type: invoices go to `Invoice` and purchase orders to `PurchaseOrder`. Any other type fails at `EvaluateRules` without retrying, before it reaches a reviewer.

The dev server keeps everything in memory. Stopping it clears every request.

## The mock systems

Every external system runs in one process on :8090. `make stop-systems` stops it, a real outage of all of them, and `make systems` starts it again.

| Endpoint | Behaves like |
|---|---|
| `POST /erp/invoices` | ERP: accepts the invoice posting with `202` at once, then POSTs the confirmation to the `callbackUrl` after `ERP_CONFIRM_DELAY`. It retries the callback until the receiver answers 2xx |
| `POST /tasks/reserve-budget` (and `/undo`) | The `PurchaseOrder` budget reservation and its compensation. A reservation is refused with `422 unknown cost center` if the cost center isn't in `data/reference-data.json`; a refusal isn't stored against its `Idempotency-Key`, so a retry or reset after the fix goes through |
| `POST /supplier-portal/purchase-orders` | Supplier portal: `200` (PO accepted), or `422` while it's set to reject |
| `POST /notify`, `POST /archive` | Notification service and archive |
| `GET`/`PUT /control` | Fault toggles: `{"erpDown": false, "supplierRejects": false}` |
| `GET /effects`, `GET /notifications` | Every side effect so far, in order |

Side-effecting calls honor an `Idempotency-Key` header: a repeat gets the first response and causes no second effect. That store is in memory, so restarting the process clears it.

`data/reference-data.json` is read on every reservation. Adding a cost center there fixes later reservations without a restart.

## Configuration

Every process reads its settings from environment variables. None reads a Temporal CLI profile.

| Variable | Default | Used for |
|---|---|---|
| `TEMPORAL_ADDRESS` | `localhost:7233` | Temporal frontend |
| `TEMPORAL_NAMESPACE` | `default` | Namespace |
| `TEMPORAL_WORKER_BUILD_ID` | `v1` | Worker version (`make worker BUILD_ID=v2` sets it) |
| `SLA_DURATION` | `60s` | Human-task SLA |
| `ERP_CONFIRM_DELAY` | `4s` | How long the ERP takes to confirm a posting |
| `CALLBACK_TIMEOUT` | `2m` | How long a request waits for the ERP's confirmation |
| `ACTIVITY_DELAY` | `500ms` | Demo-only pause at the start of every activity, so steps are spaced out in the Temporal UI. `0s` turns it off |
| `WEB_PORT` / `SYSTEMS_PORT` | `8080` / `8090` | Local ports |

## Tests

```sh
make test
```
