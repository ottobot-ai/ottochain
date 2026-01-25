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

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
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
        combiner                            <- Combiner.make[IO].pure[IO]
        ordinal                             <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        trialCid        <- UUIDGen.randomUUID[IO]
        patientCid      <- UUIDGen.randomUUID[IO]
        labCid          <- UUIDGen.randomUUID[IO]
        adverseEventCid <- UUIDGen.randomUUID[IO]
        regulatorCid    <- UUIDGen.randomUUID[IO]
        insuranceCid    <- UUIDGen.randomUUID[IO]

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
              "eventType": { "value": "start_trial" },
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.enrolledCount" }, { "var": "state.minParticipants" }] },
                  { "===": [{ "var": "machines.${regulatorCid}.state.status" }, "approved"] }
                ]
              },
              "effect": [
                ["status", "active"],
                ["startedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${regulatorCid}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "phase_1" },
              "eventType": { "value": "advance_phase" },
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 3] },
                  { "===": [{ "var": "machines.${adverseEventCid}.state.hasCriticalEvent" }, false] }
                ]
              },
              "effect": [
                ["status", "phase_1"],
                ["phase", 1],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventCid}"]
            },
            {
              "from": { "value": "phase_1" },
              "to": { "value": "phase_2" },
              "eventType": { "value": "advance_phase" },
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 8] },
                  { "===": [{ "var": "machines.${labCid}.state.passRate" }, 100] },
                  { "===": [{ "var": "machines.${adverseEventCid}.state.hasCriticalEvent" }, false] }
                ]
              },
              "effect": [
                ["status", "phase_2"],
                ["phase", 2],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labCid}", "${adverseEventCid}"]
            },
            {
              "from": { "value": "phase_2" },
              "to": { "value": "phase_3" },
              "eventType": { "value": "advance_phase" },
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 15] },
                  { ">=": [{ "var": "machines.${labCid}.state.passRate" }, 95] }
                ]
              },
              "effect": [
                ["status", "phase_3"],
                ["phase", 3],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labCid}"]
            },
            {
              "from": { "value": "phase_3" },
              "to": { "value": "under_review" },
              "eventType": { "value": "submit_for_review" },
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 20] },
                  { "===": [{ "var": "machines.${patientCid}.state.status" }, "completed"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${regulatorCid}",
                    "eventType": "begin_review",
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
              "dependencies": ["${patientCid}"]
            },
            {
              "from": { "value": "under_review" },
              "to": { "value": "completed" },
              "eventType": { "value": "finalize" },
              "guard": {
                "===": [{ "var": "machines.${regulatorCid}.state.reviewResult" }, "approved"]
              },
              "effect": [
                ["status", "completed"],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${regulatorCid}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "suspended" },
              "eventType": { "value": "suspend" },
              "guard": {
                "===": [{ "var": "machines.${adverseEventCid}.state.hasCriticalEvent" }, true]
              },
              "effect": [
                ["status", "suspended"],
                ["suspendedAt", { "var": "event.timestamp" }],
                ["suspensionReason", "critical_adverse_event"]
              ],
              "dependencies": ["${adverseEventCid}"]
            },
            {
              "from": { "value": "suspended" },
              "to": { "value": "active" },
              "eventType": { "value": "resume" },
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${adverseEventCid}.state.resolved" }, true] },
                  { "===": [{ "var": "machines.${regulatorCid}.state.allowResume" }, true] }
                ]
              },
              "effect": [
                ["status", "active"],
                ["resumedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventCid}", "${regulatorCid}"]
            },
            {
              "from": { "value": "suspended" },
              "to": { "value": "terminated" },
              "eventType": { "value": "terminate" },
              "guard": {
                "===": [{ "var": "machines.${regulatorCid}.state.terminationRequired" }, true]
              },
              "effect": [
                ["status", "terminated"],
                ["terminatedAt", { "var": "event.timestamp" }],
                ["terminationReason", { "var": "event.reason" }]
              ],
              "dependencies": ["${regulatorCid}"]
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
              "eventType": { "value": "enroll" },
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.age" }, 18] },
                  { "===": [{ "var": "event.consentSigned" }, true] },
                  { "===": [{ "var": "machines.${insuranceCid}.state.coverageActive" }, true] }
                ]
              },
              "effect": [
                ["_outputs", [
                  {
                    "outputType": "webhook",
                    "data": {
                      "event": "patient.enrolled",
                      "patientId": { "var": "machineId" },
                      "trialId": "${trialCid}",
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
              "dependencies": ["${insuranceCid}"]
            },
            {
              "from": { "value": "enrolled" },
              "to": { "value": "active" },
              "eventType": { "value": "activate" },
              "guard": {
                "===": [{ "var": "machines.${trialCid}.state.status" }, "active"]
              },
              "effect": [
                ["status", "active"],
                ["activatedAt", { "var": "event.timestamp" }],
                ["visitCount", 0]
              ],
              "dependencies": ["${trialCid}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "paused" },
              "eventType": { "value": "pause" },
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
              "eventType": { "value": "resume" },
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${trialCid}.state.status" }, "active"] },
                  { "<": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.pausedAt" }] }, 2592000] }
                ]
              },
              "effect": [
                ["status", "active"],
                ["resumedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${trialCid}"]
            },
            {
              "from": { "value": "active" },
              "to": { "value": "visit_scheduled" },
              "eventType": { "value": "schedule_visit" },
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
              "eventType": { "value": "complete_visit" },
              "guard": {
                "===": [{ "var": "machines.${labCid}.state.sampleStatus" }, "collected"]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${labCid}",
                    "eventType": "process_sample",
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
              "dependencies": ["${labCid}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "active" },
              "eventType": { "value": "continue" },
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${labCid}.state.result" }, "passed"] },
                  { "<": [{ "var": "state.visitCount" }, 20] }
                ]
              },
              "effect": [
                ["status", "active"]
              ],
              "dependencies": ["${labCid}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "adverse_event" },
              "eventType": { "value": "continue" },
              "guard": {
                "or": [
                  { "===": [{ "var": "machines.${labCid}.state.result" }, "failed"] },
                  { "===": [{ "var": "machines.${labCid}.state.result" }, "critical"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${adverseEventCid}",
                    "eventType": "report_event",
                    "payload": {
                      "patientId": { "var": "machineId" },
                      "severity": { "var": "machines.${labCid}.state.result" },
                      "detectedAt": { "var": "event.timestamp" }
                    }
                  }
                ]],
                ["status", "adverse_event"],
                ["eventDetectedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labCid}"]
            },
            {
              "from": { "value": "adverse_event" },
              "to": { "value": "active" },
              "eventType": { "value": "resolve" },
              "guard": {
                "===": [{ "var": "machines.${adverseEventCid}.state.resolved" }, true]
              },
              "effect": [
                ["status", "active"],
                ["resolvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventCid}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "completed" },
              "eventType": { "value": "complete_trial" },
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.visitCount" }, 20] },
                  { "===": [{ "var": "machines.${labCid}.state.result" }, "passed"] }
                ]
              },
              "effect": [
                ["_outputs", [
                  {
                    "outputType": "email",
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
              "dependencies": ["${labCid}"]
            },
            {
              "from": { "value": "paused" },
              "to": { "value": "withdrawn" },
              "eventType": { "value": "withdraw" },
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
              "eventType": { "value": "withdraw" },
              "guard": {
                "===": [{ "var": "machines.${adverseEventCid}.state.severity" }, "critical"]
              },
              "effect": [
                ["status", "withdrawn"],
                ["withdrawnAt", { "var": "event.timestamp" }],
                ["withdrawalReason", "critical_adverse_event"]
              ],
              "dependencies": ["${adverseEventCid}"]
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
              "eventType": { "value": "receive_sample" },
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
              "eventType": { "value": "process_sample" },
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
              "eventType": { "value": "complete" },
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
              "eventType": { "value": "complete" },
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
              "eventType": { "value": "complete" },
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
              "eventType": { "value": "complete" },
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
              "eventType": { "value": "complete" },
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
              "eventType": { "value": "retest" },
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
              "eventType": { "value": "reset" },
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
              "eventType": { "value": "reset" },
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
              "eventType": { "value": "reset" },
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
              "eventType": { "value": "reset" },
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
              "eventType": { "value": "report_event" },
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
              "eventType": { "value": "investigate" },
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
              "eventType": { "value": "resolve" },
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
              "eventType": { "value": "escalate" },
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
              "eventType": { "value": "clear" },
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
              "eventType": { "value": "start_monitoring" },
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
              "eventType": { "value": "begin_review" },
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
              "eventType": { "value": "approve" },
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
              "eventType": { "value": "approve" },
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
              "eventType": { "value": "file_claim" },
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
              "eventType": { "value": "process" },
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
              "eventType": { "value": "approve_claim" },
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
              "eventType": { "value": "pay" },
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
              "eventType": { "value": "reset" },
              "guard": true,
              "effect": [
                ["status", "active"]
              ],
              "dependencies": []
            }
          ]
        }"""

        trialDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](trialJson).left.map(err =>
            new RuntimeException(s"Failed to decode trial JSON: $err")
          )
        )

        patientDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](patientJson).left.map(err =>
            new RuntimeException(s"Failed to decode patient JSON: $err")
          )
        )

        labDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](labJson).left.map(err =>
            new RuntimeException(s"Failed to decode lab JSON: $err")
          )
        )

        adverseEventDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](adverseEventJson).left.map(err =>
            new RuntimeException(s"Failed to decode adverse event JSON: $err")
          )
        )

        regulatorDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](regulatorJson).left.map(err =>
            new RuntimeException(s"Failed to decode regulator JSON: $err")
          )
        )

        insuranceDef <- IO.fromEither(
          decode[StateMachine.StateMachineDefinition](insuranceJson).left.map(err =>
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
          cid = trialCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = trialDef,
          currentState = StateMachine.StateId("recruiting"),
          stateData = trialData,
          stateDataHash = trialHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        patientFiber = Records.StateMachineFiberRecord(
          cid = patientCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = patientDef,
          currentState = StateMachine.StateId("screening"),
          stateData = patientData,
          stateDataHash = patientHash,
          sequenceNumber = 0,
          owners = Set(Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        labFiber = Records.StateMachineFiberRecord(
          cid = labCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = labDef,
          currentState = StateMachine.StateId("idle"),
          stateData = labData,
          stateDataHash = labHash,
          sequenceNumber = 0,
          owners = Set(Charlie).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        adverseEventFiber = Records.StateMachineFiberRecord(
          cid = adverseEventCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = adverseEventDef,
          currentState = StateMachine.StateId("monitoring"),
          stateData = adverseEventData,
          stateDataHash = adverseEventHash,
          sequenceNumber = 0,
          owners = Set(Dave).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        regulatorFiber = Records.StateMachineFiberRecord(
          cid = regulatorCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = regulatorDef,
          currentState = StateMachine.StateId("approved"),
          stateData = regulatorData,
          stateDataHash = regulatorHash,
          sequenceNumber = 0,
          owners = Set(Eve).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        insuranceFiber = Records.StateMachineFiberRecord(
          cid = insuranceCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = insuranceDef,
          currentState = StateMachine.StateId("active"),
          stateData = insuranceData,
          stateDataHash = insuranceHash,
          sequenceNumber = 0,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(
            Map(
              trialCid        -> trialHash,
              patientCid      -> patientHash,
              labCid          -> labHash,
              adverseEventCid -> adverseEventHash,
              regulatorCid    -> regulatorHash,
              insuranceCid    -> insuranceHash
            )
          ),
          CalculatedState(
            Map(
              trialCid        -> trialFiber,
              patientCid      -> patientFiber,
              labCid          -> labFiber,
              adverseEventCid -> adverseEventFiber,
              regulatorCid    -> regulatorFiber,
              insuranceCid    -> insuranceFiber
            ),
            Map.empty
          )
        )

        // Step 1: Enroll patient
        enrollEvent = StateMachine.Event(
          eventType = StateMachine.EventType("enroll"),
          payload = MapValue(
            Map(
              "timestamp"     -> IntValue(1000),
              "age"           -> IntValue(45),
              "consentSigned" -> BoolValue(true),
              "patientName"   -> StrValue("John Doe")
            )
          )
        )
        enrollUpdate = Updates.ProcessFiberEvent(patientCid, enrollEvent)
        enrollProof <- registry.generateProofs(enrollUpdate, Set(Bob))
        state1      <- combiner.insert(inState, Signed(enrollUpdate, enrollProof))

        // Step 2: Start trial
        startTrialEvent = StateMachine.Event(
          eventType = StateMachine.EventType("start_trial"),
          payload = MapValue(Map("timestamp" -> IntValue(1100)))
        )
        startTrialUpdate = Updates.ProcessFiberEvent(trialCid, startTrialEvent)
        startTrialProof <- registry.generateProofs(startTrialUpdate, Set(Alice))
        state2          <- combiner.insert(state1, Signed(startTrialUpdate, startTrialProof))

        // Step 3: Activate patient
        activateEvent = StateMachine.Event(
          eventType = StateMachine.EventType("activate"),
          payload = MapValue(Map("timestamp" -> IntValue(1200)))
        )
        activateUpdate = Updates.ProcessFiberEvent(patientCid, activateEvent)
        activateProof <- registry.generateProofs(activateUpdate, Set(Bob))
        state3        <- combiner.insert(state2, Signed(activateUpdate, activateProof))

        // Step 4: Schedule visit
        scheduleVisitEvent = StateMachine.Event(
          eventType = StateMachine.EventType("schedule_visit"),
          payload = MapValue(Map("visitTime" -> IntValue(2000)))
        )
        scheduleVisitUpdate = Updates.ProcessFiberEvent(patientCid, scheduleVisitEvent)
        scheduleVisitProof <- registry.generateProofs(scheduleVisitUpdate, Set(Bob))
        state4             <- combiner.insert(state3, Signed(scheduleVisitUpdate, scheduleVisitProof))

        // Step 5: Lab receives sample
        receiveSampleEvent = StateMachine.Event(
          eventType = StateMachine.EventType("receive_sample"),
          payload = MapValue(Map("timestamp" -> IntValue(2100)))
        )
        receiveSampleUpdate = Updates.ProcessFiberEvent(labCid, receiveSampleEvent)
        receiveSampleProof <- registry.generateProofs(receiveSampleUpdate, Set(Charlie))
        state5             <- combiner.insert(state4, Signed(receiveSampleUpdate, receiveSampleProof))

        // Step 6: Complete visit (triggers lab processing)
        completeVisitEvent = StateMachine.Event(
          eventType = StateMachine.EventType("complete_visit"),
          payload = MapValue(Map("timestamp" -> IntValue(2200)))
        )
        completeVisitUpdate = Updates.ProcessFiberEvent(patientCid, completeVisitEvent)
        completeVisitProof <- registry.generateProofs(completeVisitUpdate, Set(Bob))
        state6             <- combiner.insert(state5, Signed(completeVisitUpdate, completeVisitProof))

        // Verify trigger fired and lab is now processing
        labAfterTrigger = state6.calculated.stateMachines
          .get(labCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        labProcessingStatus: Option[String] = labAfterTrigger.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        // Step 7: Complete lab processing with high quality (passed)
        completeLabEvent = StateMachine.Event(
          eventType = StateMachine.EventType("complete"),
          payload = MapValue(
            Map(
              "timestamp"      -> IntValue(2300),
              "qualityScore"   -> IntValue(95),
              "contaminated"   -> BoolValue(false),
              "biomarkerLevel" -> IntValue(50)
            )
          )
        )
        completeLabUpdate = Updates.ProcessFiberEvent(labCid, completeLabEvent)
        completeLabProof <- registry.generateProofs(completeLabUpdate, Set(Charlie))
        state7           <- combiner.insert(state6, Signed(completeLabUpdate, completeLabProof))

        labAfterComplete = state7.calculated.stateMachines
          .get(labCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        labResult: Option[String] = labAfterComplete.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("result").collect { case StrValue(r) => r }
            case _           => None
          }
        }

        // Step 8: Patient continues after passed result
        continueEvent = StateMachine.Event(
          eventType = StateMachine.EventType("continue"),
          payload = MapValue(Map("timestamp" -> IntValue(2400)))
        )
        continueUpdate = Updates.ProcessFiberEvent(patientCid, continueEvent)
        continueProof <- registry.generateProofs(continueUpdate, Set(Bob))
        state8        <- combiner.insert(state7, Signed(continueUpdate, continueProof))

        patientAfterContinue = state8.calculated.stateMachines
          .get(patientCid)
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
          .get(trialCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        trialStatus: Option[String] = trialAfterVisit.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

      } yield expect.all(
        // Verify patient enrollment succeeded
        state1.calculated.stateMachines.get(patientCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateMachine.StateId("enrolled")
          case _                                  => false
        },
        // Verify trial started
        state2.calculated.stateMachines.get(trialCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateMachine.StateId("active")
          case _                                  => false
        },
        // Verify patient activated
        state3.calculated.stateMachines.get(patientCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateMachine.StateId("active")
          case _                                  => false
        },
        // Verify visit scheduled
        state4.calculated.stateMachines.get(patientCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateMachine.StateId("visit_scheduled")
          case _                                  => false
        },
        // Verify lab received sample
        state5.calculated.stateMachines.get(labCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateMachine.StateId("sample_received")
          case _                                  => false
        },
        // Verify trigger fired: lab is processing after visit completion
        labAfterTrigger.isDefined,
        labProcessingStatus.contains("processing"),
        labAfterTrigger.map(_.currentState).contains(StateMachine.StateId("processing")),
        // Verify lab completed with passed result (demonstrates multiple guards on same event)
        labAfterComplete.isDefined,
        labAfterComplete.map(_.currentState).contains(StateMachine.StateId("passed")),
        labResult.contains("passed"),
        // Verify patient continued and is back to active
        patientAfterContinue.isDefined,
        patientAfterContinue.map(_.currentState).contains(StateMachine.StateId("active")),
        patientStatus.contains("active"),
        patientVisitCount.contains(BigInt(1)),
        // Verify trial is still active
        trialAfterVisit.isDefined,
        trialStatus.contains("active")
      )
    }
  }
}
