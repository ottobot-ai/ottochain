package xyz.kd5ujc.shared_data.examples

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant._

import io.circe.parser._
import weaver.SimpleIOSuite

object ClinicalTrialStateMachineSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("json-encoded: clinical trial with multi-party coordination and bi-directional transitions") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry                            <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie, Dave, Eve))
        combiner                            <- Combiner.make[IO]().pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        trialfiberId        <- UUIDGen.randomUUID[IO]
        patientfiberId      <- UUIDGen.randomUUID[IO]
        labfiberId          <- UUIDGen.randomUUID[IO]
        adverseEventfiberId <- UUIDGen.randomUUID[IO]
        regulatorfiberId    <- UUIDGen.randomUUID[IO]
        insurancefiberId    <- UUIDGen.randomUUID[IO]

        // Trial Coordinator: manages overall trial phases
        trialJson =
          s"""{
          "states": {
            "recruiting": { "id": { "value": "recruiting" }, "isFinal": false },
            "active": { "id": { "value": "active" }, "isFinal": false },
            "phase_1": { "id": { "value": "phase_1" }, "isFinal": false },
            "phase_2": { "id": { "value": "phase_2" }, "isFinal": false },
            "phase_3": { "id": { "value": "phase_3" }, "isFinal": false },
            "under_review": { "id": { "value": "under_review" }, "isFinal": false },
            "completed": { "id": { "value": "completed" }, "isFinal": true },
            "suspended": { "id": { "value": "suspended" }, "isFinal": false },
            "terminated": { "id": { "value": "terminated" }, "isFinal": true }
          },
          "initialState": { "value": "recruiting" },
          "transitions": [
            {
              "from": { "value": "recruiting" },
              "to": { "value": "active" },
              "eventName": "start_trial",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.enrolledCount" }, { "var": "state.minParticipants" }] },
                  { "===": [{ "var": "machines.${regulatorfiberId}.state.status" }, "approved"] }
                ]
              },
              "effect": [
                ["status", "active"],
                ["startedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${regulatorfiberId}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "phase_1" },
              "eventName": "advance_phase",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 3] },
                  { "===": [{ "var": "machines.${adverseEventfiberId}.state.hasCriticalEvent" }, false] }
                ]
              },
              "effect": [
                ["status", "phase_1"],
                ["phase", 1],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            },
            {
              "from": { "value": "phase_1" },
              "to": { "value": "phase_2" },
              "eventName": "advance_phase",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 8] },
                  { "===": [{ "var": "machines.${labfiberId}.state.passRate" }, 100] },
                  { "===": [{ "var": "machines.${adverseEventfiberId}.state.hasCriticalEvent" }, false] }
                ]
              },
              "effect": [
                ["status", "phase_2"],
                ["phase", 2],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}", "${adverseEventfiberId}"]
            },
            {
              "from": { "value": "phase_2" },
              "to": { "value": "phase_3" },
              "eventName": "advance_phase",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 15] },
                  { ">=": [{ "var": "machines.${labfiberId}.state.passRate" }, 95] }
                ]
              },
              "effect": [
                ["status", "phase_3"],
                ["phase", 3],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "phase_3" },
              "to": { "value": "under_review" },
              "eventName": "submit_for_review",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 20] },
                  { "===": [{ "var": "machines.${patientfiberId}.state.status" }, "completed"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${regulatorfiberId}",
                    "eventName": "begin_review",
                    "payload": {
                      "trialId": { "var": "machineId" },
                      "submittedAt": { "var": "event.timestamp" },
                      "completedVisits": { "var": "state.completedVisits" }
                    }
                  }
                ]],
                ["status", "under_review"],
                ["submittedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${patientfiberId}"]
            },
            {
              "from": { "value": "under_review" },
              "to": { "value": "completed" },
              "eventName": "finalize",
              "guard": {
                "===": [{ "var": "machines.${regulatorfiberId}.state.reviewResult" }, "approved"]
              },
              "effect": [
                ["status", "completed"],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${regulatorfiberId}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "suspended" },
              "eventName": "suspend",
              "guard": {
                "===": [{ "var": "machines.${adverseEventfiberId}.state.hasCriticalEvent" }, true]
              },
              "effect": [
                ["status", "suspended"],
                ["suspendedAt", { "var": "event.timestamp" }],
                ["suspensionReason", "critical_adverse_event"]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            },
            {
              "from": { "value": "suspended" },
              "to": { "value": "active" },
              "eventName": "resume",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${adverseEventfiberId}.state.resolved" }, true] },
                  { "===": [{ "var": "machines.${regulatorfiberId}.state.allowResume" }, true] }
                ]
              },
              "effect": [
                ["status", "active"],
                ["resumedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventfiberId}", "${regulatorfiberId}"]
            },
            {
              "from": { "value": "suspended" },
              "to": { "value": "terminated" },
              "eventName": "terminate",
              "guard": {
                "===": [{ "var": "machines.${regulatorfiberId}.state.terminationRequired" }, true]
              },
              "effect": [
                ["status", "terminated"],
                ["terminatedAt", { "var": "event.timestamp" }],
                ["terminationReason", { "var": "event.reason" }]
              ],
              "dependencies": ["${regulatorfiberId}"]
            }
          ]
        }"""

        // Patient Enrollment: demonstrates bi-directional transitions
        patientJson =
          s"""{
          "states": {
            "screening": { "id": { "value": "screening" }, "isFinal": false },
            "enrolled": { "id": { "value": "enrolled" }, "isFinal": false },
            "active": { "id": { "value": "active" }, "isFinal": false },
            "paused": { "id": { "value": "paused" }, "isFinal": false },
            "visit_scheduled": { "id": { "value": "visit_scheduled" }, "isFinal": false },
            "visit_completed": { "id": { "value": "visit_completed" }, "isFinal": false },
            "adverse_event": { "id": { "value": "adverse_event" }, "isFinal": false },
            "completed": { "id": { "value": "completed" }, "isFinal": true },
            "withdrawn": { "id": { "value": "withdrawn" }, "isFinal": true }
          },
          "initialState": { "value": "screening" },
          "transitions": [
            {
              "from": { "value": "screening" },
              "to": { "value": "enrolled" },
              "eventName": "enroll",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.age" }, 18] },
                  { "===": [{ "var": "event.consentSigned" }, true] },
                  { "===": [{ "var": "machines.${insurancefiberId}.state.coverageActive" }, true] }
                ]
              },
              "effect": [
                ["_emit", [
                  {
                    "name": "webhook",
                    "data": {
                      "event": "patient.enrolled",
                      "patientId": { "var": "machineId" },
                      "trialId": "${trialfiberId}",
                      "timestamp": { "var": "event.timestamp" }
                    },
                    "destination": "https://trial-system.example.com/webhooks"
                  }
                ]],
                ["status", "enrolled"],
                ["enrolledAt", { "var": "event.timestamp" }],
                ["patientName", { "var": "event.patientName" }],
                ["age", { "var": "event.age" }]
              ],
              "dependencies": ["${insurancefiberId}"]
            },
            {
              "from": { "value": "enrolled" },
              "to": { "value": "active" },
              "eventName": "activate",
              "guard": {
                "===": [{ "var": "machines.${trialfiberId}.state.status" }, "active"]
              },
              "effect": [
                ["status", "active"],
                ["activatedAt", { "var": "event.timestamp" }],
                ["visitCount", 0]
              ],
              "dependencies": ["${trialfiberId}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "paused" },
              "eventName": "pause",
              "guard": true,
              "effect": [
                ["status", "paused"],
                ["pausedAt", { "var": "event.timestamp" }],
                ["pauseReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "paused" },
              "to": { "value": "active" },
              "eventName": "resume",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${trialfiberId}.state.status" }, "active"] },
                  { "<": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.pausedAt" }] }, 2592000] }
                ]
              },
              "effect": [
                ["status", "active"],
                ["resumedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${trialfiberId}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "visit_scheduled" },
              "eventName": "schedule_visit",
              "guard": {
                "<": [{ "var": "state.visitCount" }, 20]
              },
              "effect": [
                ["status", "visit_scheduled"],
                ["nextVisitAt", { "var": "event.visitTime" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "visit_scheduled" },
              "to": { "value": "visit_completed" },
              "eventName": "complete_visit",
              "guard": {
                "===": [{ "var": "machines.${labfiberId}.state.sampleStatus" }, "collected"]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${labfiberId}",
                    "eventName": "process_sample",
                    "payload": {
                      "patientId": { "var": "machineId" },
                      "visitNumber": { "+": [{ "var": "state.visitCount" }, 1] },
                      "collectedAt": { "var": "event.timestamp" }
                    }
                  }
                ]],
                ["status", "visit_completed"],
                ["visitCount", { "+": [{ "var": "state.visitCount" }, 1] }],
                ["lastVisitAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "active" },
              "eventName": "continue",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "passed"] },
                  { "<": [{ "var": "state.visitCount" }, 20] }
                ]
              },
              "effect": [
                ["status", "active"]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "adverse_event" },
              "eventName": "continue",
              "guard": {
                "or": [
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "failed"] },
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "critical"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${adverseEventfiberId}",
                    "eventName": "report_event",
                    "payload": {
                      "patientId": { "var": "machineId" },
                      "severity": { "var": "machines.${labfiberId}.state.result" },
                      "detectedAt": { "var": "event.timestamp" }
                    }
                  }
                ]],
                ["status", "adverse_event"],
                ["eventDetectedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "adverse_event" },
              "to": { "value": "active" },
              "eventName": "resolve",
              "guard": {
                "===": [{ "var": "machines.${adverseEventfiberId}.state.resolved" }, true]
              },
              "effect": [
                ["status", "active"],
                ["resolvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "completed" },
              "eventName": "complete_trial",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.visitCount" }, 20] },
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "passed"] }
                ]
              },
              "effect": [
                ["_emit", [
                  {
                    "name": "email",
                    "data": {
                      "to": { "var": "state.patientEmail" },
                      "subject": "Trial Completion",
                      "body": "Congratulations on completing the clinical trial"
                    }
                  }
                ]],
                ["status", "completed"],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "paused" },
              "to": { "value": "withdrawn" },
              "eventName": "withdraw",
              "guard": true,
              "effect": [
                ["status", "withdrawn"],
                ["withdrawnAt", { "var": "event.timestamp" }],
                ["withdrawalReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "adverse_event" },
              "to": { "value": "withdrawn" },
              "eventName": "withdraw",
              "guard": {
                "===": [{ "var": "machines.${adverseEventfiberId}.state.severity" }, "critical"]
              },
              "effect": [
                ["status", "withdrawn"],
                ["withdrawnAt", { "var": "event.timestamp" }],
                ["withdrawalReason", "critical_adverse_event"]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            }
          ]
        }"""

        // Lab Results: demonstrates multiple outcomes from same event
        labJson =
          """{
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "sample_received": { "id": { "value": "sample_received" }, "isFinal": false },
            "processing": { "id": { "value": "processing" }, "isFinal": false },
            "passed": { "id": { "value": "passed" }, "isFinal": false },
            "questionable": { "id": { "value": "questionable" }, "isFinal": false },
            "failed": { "id": { "value": "failed" }, "isFinal": false },
            "critical": { "id": { "value": "critical" }, "isFinal": false },
            "retest_required": { "id": { "value": "retest_required" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "sample_received" },
              "eventName": "receive_sample",
              "guard": true,
              "effect": [
                ["sampleStatus", "collected"],
                ["receivedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "sample_received" },
              "to": { "value": "processing" },
              "eventName": "process_sample",
              "guard": true,
              "effect": [
                ["status", "processing"],
                ["sampleStatus", "processing"],
                ["processingStartedAt", { "var": "event.timestamp" }],
                ["patientId", { "var": "event.patientId" }],
                ["visitNumber", { "var": "event.visitNumber" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "passed" },
              "eventName": "complete",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.qualityScore" }, 90] },
                  { "===": [{ "var": "event.contaminated" }, false] },
                  { "<": [{ "var": "event.biomarkerLevel" }, 100] }
                ]
              },
              "effect": [
                ["result", "passed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 100],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "questionable" },
              "eventName": "complete",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.qualityScore" }, 70] },
                  { "<": [{ "var": "event.qualityScore" }, 90] },
                  { "===": [{ "var": "event.contaminated" }, false] }
                ]
              },
              "effect": [
                ["result", "questionable"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 95],
                ["completedAt", { "var": "event.timestamp" }],
                ["reviewRequired", true]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "failed" },
              "eventName": "complete",
              "guard": {
                "and": [
                  { "<": [{ "var": "event.qualityScore" }, 70] },
                  { "===": [{ "var": "event.contaminated" }, false] }
                ]
              },
              "effect": [
                ["result", "failed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 80],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "critical" },
              "eventName": "complete",
              "guard": {
                ">=": [{ "var": "event.biomarkerLevel" }, 500]
              },
              "effect": [
                ["result", "critical"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 70],
                ["completedAt", { "var": "event.timestamp" }],
                ["criticalAlert", true]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "retest_required" },
              "eventName": "complete",
              "guard": {
                "===": [{ "var": "event.contaminated" }, true]
              },
              "effect": [
                ["result", "retest_required"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["passRate", 90],
                ["completedAt", { "var": "event.timestamp" }],
                ["contaminationReason", { "var": "event.contaminationReason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "retest_required" },
              "to": { "value": "processing" },
              "eventName": "retest",
              "guard": true,
              "effect": [
                ["status", "processing"],
                ["sampleStatus", "processing"],
                ["retestCount", { "+": [{ "var": "state.retestCount" }, 1] }],
                ["retestStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "passed" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "questionable" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "failed" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "critical" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Adverse Event Monitor
        adverseEventJson =
          """{
          "states": {
            "monitoring": { "id": { "value": "monitoring" }, "isFinal": false },
            "event_reported": { "id": { "value": "event_reported" }, "isFinal": false },
            "investigating": { "id": { "value": "investigating" }, "isFinal": false },
            "resolved": { "id": { "value": "resolved" }, "isFinal": false },
            "escalated": { "id": { "value": "escalated" }, "isFinal": true }
          },
          "initialState": { "value": "monitoring" },
          "transitions": [
            {
              "from": { "value": "monitoring" },
              "to": { "value": "event_reported" },
              "eventName": "report_event",
              "guard": true,
              "effect": [
                ["hasCriticalEvent", { "===": [{ "var": "event.severity" }, "critical"] }],
                ["severity", { "var": "event.severity" }],
                ["patientId", { "var": "event.patientId" }],
                ["reportedAt", { "var": "event.detectedAt" }],
                ["resolved", false]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "event_reported" },
              "to": { "value": "investigating" },
              "eventName": "investigate",
              "guard": true,
              "effect": [
                ["status", "investigating"],
                ["investigationStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "resolved" },
              "eventName": "resolve",
              "guard": {
                "!==": [{ "var": "state.severity" }, "critical"]
              },
              "effect": [
                ["status", "resolved"],
                ["resolved", true],
                ["hasCriticalEvent", false],
                ["resolvedAt", { "var": "event.timestamp" }],
                ["resolution", { "var": "event.resolution" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "escalated" },
              "eventName": "escalate",
              "guard": {
                "===": [{ "var": "state.severity" }, "critical"]
              },
              "effect": [
                ["status", "escalated"],
                ["hasCriticalEvent", true],
                ["escalatedAt", { "var": "event.timestamp" }],
                ["escalationReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "resolved" },
              "to": { "value": "monitoring" },
              "eventName": "clear",
              "guard": true,
              "effect": [
                ["status", "monitoring"],
                ["hasCriticalEvent", false],
                ["resolved", true]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Regulatory Review
        regulatorJson =
          """{
          "states": {
            "approved": { "id": { "value": "approved" }, "isFinal": false },
            "monitoring": { "id": { "value": "monitoring" }, "isFinal": false },
            "reviewing": { "id": { "value": "reviewing" }, "isFinal": false },
            "final_approved": { "id": { "value": "final_approved" }, "isFinal": true },
            "rejected": { "id": { "value": "rejected" }, "isFinal": true }
          },
          "initialState": { "value": "approved" },
          "transitions": [
            {
              "from": { "value": "approved" },
              "to": { "value": "monitoring" },
              "eventName": "start_monitoring",
              "guard": true,
              "effect": [
                ["status", "monitoring"],
                ["allowResume", true],
                ["terminationRequired", false],
                ["monitoringStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "monitoring" },
              "to": { "value": "reviewing" },
              "eventName": "begin_review",
              "guard": true,
              "effect": [
                ["status", "reviewing"],
                ["reviewStartedAt", { "var": "event.submittedAt" }],
                ["trialId", { "var": "event.trialId" }],
                ["completedVisits", { "var": "event.completedVisits" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "final_approved" },
              "eventName": "approve",
              "guard": {
                ">=": [{ "var": "event.complianceScore" }, 90]
              },
              "effect": [
                ["reviewResult", "approved"],
                ["status", "final_approved"],
                ["approvedAt", { "var": "event.timestamp" }],
                ["complianceScore", { "var": "event.complianceScore" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "rejected" },
              "eventName": "approve",
              "guard": {
                "<": [{ "var": "event.complianceScore" }, 90]
              },
              "effect": [
                ["reviewResult", "rejected"],
                ["status", "rejected"],
                ["rejectedAt", { "var": "event.timestamp" }],
                ["complianceScore", { "var": "event.complianceScore" }],
                ["rejectionReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Insurance Coverage
        insuranceJson =
          """{
          "states": {
            "active": { "id": { "value": "active" }, "isFinal": false },
            "claim_filed": { "id": { "value": "claim_filed" }, "isFinal": false },
            "claim_processing": { "id": { "value": "claim_processing" }, "isFinal": false },
            "claim_approved": { "id": { "value": "claim_approved" }, "isFinal": false },
            "claim_paid": { "id": { "value": "claim_paid" }, "isFinal": false }
          },
          "initialState": { "value": "active" },
          "transitions": [
            {
              "from": { "value": "active" },
              "to": { "value": "claim_filed" },
              "eventName": "file_claim",
              "guard": {
                ">=": [{ "var": "state.coverage" }, { "var": "event.claimAmount" }]
              },
              "effect": [
                ["status", "claim_filed"],
                ["claimAmount", { "var": "event.claimAmount" }],
                ["claimFiledAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_filed" },
              "to": { "value": "claim_processing" },
              "eventName": "process",
              "guard": true,
              "effect": [
                ["status", "claim_processing"],
                ["processingStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_processing" },
              "to": { "value": "claim_approved" },
              "eventName": "approve_claim",
              "guard": true,
              "effect": [
                ["status", "claim_approved"],
                ["approvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_approved" },
              "to": { "value": "claim_paid" },
              "eventName": "pay",
              "guard": true,
              "effect": [
                ["status", "claim_paid"],
                ["paidAt", { "var": "event.timestamp" }],
                ["remainingCoverage", { "-": [{ "var": "state.coverage" }, { "var": "state.claimAmount" }] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_paid" },
              "to": { "value": "active" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["status", "active"]
              ],
              "dependencies": []
            }
          ]
        }"""

        trialDef <- IO.fromEither(
          decode[StateMachineDefinition](trialJson).left.map(err =>
            new RuntimeException(s"Failed to decode trial JSON: $err")
          )
        )

        patientDef <- IO.fromEither(
          decode[StateMachineDefinition](patientJson).left.map(err =>
            new RuntimeException(s"Failed to decode patient JSON: $err")
          )
        )

        labDef <- IO.fromEither(
          decode[StateMachineDefinition](labJson).left.map(err =>
            new RuntimeException(s"Failed to decode lab JSON: $err")
          )
        )

        adverseEventDef <- IO.fromEither(
          decode[StateMachineDefinition](adverseEventJson).left.map(err =>
            new RuntimeException(s"Failed to decode adverse event JSON: $err")
          )
        )

        regulatorDef <- IO.fromEither(
          decode[StateMachineDefinition](regulatorJson).left.map(err =>
            new RuntimeException(s"Failed to decode regulator JSON: $err")
          )
        )

        insuranceDef <- IO.fromEither(
          decode[StateMachineDefinition](insuranceJson).left.map(err =>
            new RuntimeException(s"Failed to decode insurance JSON: $err")
          )
        )

        trialData = MapValue(
          Map(
            "trialName"       -> StrValue("Phase III Cancer Treatment Trial"),
            "minParticipants" -> IntValue(1),
            "enrolledCount"   -> IntValue(1),
            "completedVisits" -> IntValue(0),
            "phase"           -> IntValue(0),
            "status"          -> StrValue("recruiting")
          )
        )
        trialHash <- (trialData: JsonLogicValue).computeDigest

        patientData = MapValue(
          Map(
            "patientName"  -> StrValue("John Doe"),
            "patientEmail" -> StrValue("john.doe@example.com"),
            "status"       -> StrValue("screening")
          )
        )
        patientHash <- (patientData: JsonLogicValue).computeDigest

        labData = MapValue(
          Map(
            "labName"      -> StrValue("Central Lab"),
            "sampleStatus" -> StrValue("idle"),
            "passRate"     -> IntValue(100),
            "retestCount"  -> IntValue(0)
          )
        )
        labHash <- (labData: JsonLogicValue).computeDigest

        adverseEventData = MapValue(
          Map(
            "status"           -> StrValue("monitoring"),
            "hasCriticalEvent" -> BoolValue(false),
            "resolved"         -> BoolValue(true)
          )
        )
        adverseEventHash <- (adverseEventData: JsonLogicValue).computeDigest

        regulatorData = MapValue(
          Map(
            "regulatorName"       -> StrValue("FDA"),
            "status"              -> StrValue("approved"),
            "allowResume"         -> BoolValue(true),
            "terminationRequired" -> BoolValue(false)
          )
        )
        regulatorHash <- (regulatorData: JsonLogicValue).computeDigest

        insuranceData = MapValue(
          Map(
            "policyNumber"   -> StrValue("POL-123456"),
            "coverage"       -> IntValue(100000),
            "coverageActive" -> BoolValue(true),
            "status"         -> StrValue("active")
          )
        )
        insuranceHash <- (insuranceData: JsonLogicValue).computeDigest

        trialFiber = Records.StateMachineFiberRecord(
          fiberId = trialfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = trialDef,
          currentState = StateId("recruiting"),
          stateData = trialData,
          stateDataHash = trialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        patientFiber = Records.StateMachineFiberRecord(
          fiberId = patientfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = patientDef,
          currentState = StateId("screening"),
          stateData = patientData,
          stateDataHash = patientHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Bob).map(registry.addresses),
          status = FiberStatus.Active
        )

        labFiber = Records.StateMachineFiberRecord(
          fiberId = labfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = labDef,
          currentState = StateId("idle"),
          stateData = labData,
          stateDataHash = labHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Charlie).map(registry.addresses),
          status = FiberStatus.Active
        )

        adverseEventFiber = Records.StateMachineFiberRecord(
          fiberId = adverseEventfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = adverseEventDef,
          currentState = StateId("monitoring"),
          stateData = adverseEventData,
          stateDataHash = adverseEventHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Dave).map(registry.addresses),
          status = FiberStatus.Active
        )

        regulatorFiber = Records.StateMachineFiberRecord(
          fiberId = regulatorfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = regulatorDef,
          currentState = StateId("approved"),
          stateData = regulatorData,
          stateDataHash = regulatorHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Eve).map(registry.addresses),
          status = FiberStatus.Active
        )

        insuranceFiber = Records.StateMachineFiberRecord(
          fiberId = insurancefiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = insuranceDef,
          currentState = StateId("active"),
          stateData = insuranceData,
          stateDataHash = insuranceHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            trialfiberId        -> trialFiber,
            patientfiberId      -> patientFiber,
            labfiberId          -> labFiber,
            adverseEventfiberId -> adverseEventFiber,
            regulatorfiberId    -> regulatorFiber,
            insurancefiberId    -> insuranceFiber
          )
        )

        // Step 1: Enroll patient
        enrollUpdate = Updates.TransitionStateMachine(
          patientfiberId,
          "enroll",
          MapValue(
            Map(
              "timestamp"     -> IntValue(1000),
              "age"           -> IntValue(45),
              "consentSigned" -> BoolValue(true),
              "patientName"   -> StrValue("John Doe")
            )
          ),
          FiberOrdinal.MinValue
        )
        enrollProof <- registry.generateProofs(enrollUpdate, Set(Bob))
        state1      <- combiner.insert(inState, Signed(enrollUpdate, enrollProof))

        // Step 2: Start trial
        startTrialUpdate = Updates.TransitionStateMachine(
          trialfiberId,
          "start_trial",
          MapValue(Map("timestamp" -> IntValue(1100))),
          FiberOrdinal.MinValue
        )
        startTrialProof <- registry.generateProofs(startTrialUpdate, Set(Alice))
        state2          <- combiner.insert(state1, Signed(startTrialUpdate, startTrialProof))

        // Step 3: Activate patient
        activateSeqNum = state2.calculated.stateMachines(patientfiberId).sequenceNumber
        activateUpdate = Updates.TransitionStateMachine(
          patientfiberId,
          "activate",
          MapValue(Map("timestamp" -> IntValue(1200))),
          activateSeqNum
        )
        activateProof <- registry.generateProofs(activateUpdate, Set(Bob))
        state3        <- combiner.insert(state2, Signed(activateUpdate, activateProof))

        // Step 4: Schedule visit
        scheduleVisitSeqNum = state3.calculated.stateMachines(patientfiberId).sequenceNumber
        scheduleVisitUpdate = Updates.TransitionStateMachine(
          patientfiberId,
          "schedule_visit",
          MapValue(Map("visitTime" -> IntValue(2000))),
          scheduleVisitSeqNum
        )
        scheduleVisitProof <- registry.generateProofs(scheduleVisitUpdate, Set(Bob))
        state4             <- combiner.insert(state3, Signed(scheduleVisitUpdate, scheduleVisitProof))

        // Step 5: Lab receives sample
        receiveSampleUpdate = Updates.TransitionStateMachine(
          labfiberId,
          "receive_sample",
          MapValue(Map("timestamp" -> IntValue(2100))),
          FiberOrdinal.MinValue
        )
        receiveSampleProof <- registry.generateProofs(receiveSampleUpdate, Set(Charlie))
        state5             <- combiner.insert(state4, Signed(receiveSampleUpdate, receiveSampleProof))

        // Step 6: Complete visit (triggers lab processing)
        completeVisitSeqNum = state5.calculated.stateMachines(patientfiberId).sequenceNumber
        completeVisitUpdate = Updates.TransitionStateMachine(
          patientfiberId,
          "complete_visit",
          MapValue(Map("timestamp" -> IntValue(2200))),
          completeVisitSeqNum
        )
        completeVisitProof <- registry.generateProofs(completeVisitUpdate, Set(Bob))
        state6             <- combiner.insert(state5, Signed(completeVisitUpdate, completeVisitProof))

        // Verify trigger fired and lab is now processing
        labAfterTrigger = state6.calculated.stateMachines
          .get(labfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        labProcessingStatus: Option[String] = labAfterTrigger.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        // Step 7: Complete lab processing with high quality (passed)
        completeLabSeqNum = state6.calculated.stateMachines(labfiberId).sequenceNumber
        completeLabUpdate = Updates.TransitionStateMachine(
          labfiberId,
          "complete",
          MapValue(
            Map(
              "timestamp"      -> IntValue(2300),
              "qualityScore"   -> IntValue(95),
              "contaminated"   -> BoolValue(false),
              "biomarkerLevel" -> IntValue(50)
            )
          ),
          completeLabSeqNum
        )
        completeLabProof <- registry.generateProofs(completeLabUpdate, Set(Charlie))
        state7           <- combiner.insert(state6, Signed(completeLabUpdate, completeLabProof))

        labAfterComplete = state7.calculated.stateMachines
          .get(labfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        labResult: Option[String] = labAfterComplete.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("result").collect { case StrValue(r) => r }
            case _           => None
          }
        }

        // Step 8: Patient continues after passed result
        continueSeqNum = state7.calculated.stateMachines(patientfiberId).sequenceNumber
        continueUpdate = Updates.TransitionStateMachine(
          patientfiberId,
          "continue",
          MapValue(Map("timestamp" -> IntValue(2400))),
          continueSeqNum
        )
        continueProof <- registry.generateProofs(continueUpdate, Set(Bob))
        state8        <- combiner.insert(state7, Signed(continueUpdate, continueProof))

        patientAfterContinue = state8.calculated.stateMachines
          .get(patientfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        patientStatus: Option[String] = patientAfterContinue.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        patientVisitCount: Option[BigInt] = patientAfterContinue.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("visitCount").collect { case IntValue(c) => c }
            case _           => None
          }
        }

        trialAfterVisit = state8.calculated.stateMachines
          .get(trialfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        trialStatus: Option[String] = trialAfterVisit.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        // Verify patient enrollment succeeded
        state1.calculated.stateMachines.get(patientfiberId).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("enrolled")
          case _                                  => false
        },
        // Verify trial started
        state2.calculated.stateMachines.get(trialfiberId).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("active")
          case _                                  => false
        },
        // Verify patient activated
        state3.calculated.stateMachines.get(patientfiberId).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("active")
          case _                                  => false
        },
        // Verify visit scheduled
        state4.calculated.stateMachines.get(patientfiberId).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("visit_scheduled")
          case _                                  => false
        },
        // Verify lab received sample
        state5.calculated.stateMachines.get(labfiberId).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("sample_received")
          case _                                  => false
        },
        // Verify trigger fired: lab is processing after visit completion
        labAfterTrigger.isDefined,
        labProcessingStatus.contains("processing"),
        labAfterTrigger.map(_.currentState).contains(StateId("processing")),
        // Verify lab completed with passed result (demonstrates multiple guards on same event)
        labAfterComplete.isDefined,
        labAfterComplete.map(_.currentState).contains(StateId("passed")),
        labResult.contains("passed"),
        // Verify patient continued and is back to active
        patientAfterContinue.isDefined,
        patientAfterContinue.map(_.currentState).contains(StateId("active")),
        patientStatus.contains("active"),
        patientVisitCount.contains(BigInt(1)),
        // Verify trial is still active
        trialAfterVisit.isDefined,
        trialStatus.contains("active")
      )
    }
  }
}
