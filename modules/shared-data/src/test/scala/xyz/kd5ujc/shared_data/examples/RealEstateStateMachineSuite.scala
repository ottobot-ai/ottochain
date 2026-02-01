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

object RealEstateStateMachineSuite extends SimpleIOSuite {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  test("json-encoded: complete real estate lifecycle from contract to foreclosure") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]
        registry <- ParticipantRegistry.create[IO](Set(Alice, Bob, Charlie, Dave, Eve, Faythe, Grace, Heidi))
        combiner <- Combiner.make[IO]().pure[IO]
        ordinal  <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        propertyCid           <- UUIDGen.randomUUID[IO]
        contractCid           <- UUIDGen.randomUUID[IO]
        escrowCid             <- UUIDGen.randomUUID[IO]
        inspectionCid         <- UUIDGen.randomUUID[IO]
        appraisalCid          <- UUIDGen.randomUUID[IO]
        mortgageCid           <- UUIDGen.randomUUID[IO]
        titleCid              <- UUIDGen.randomUUID[IO]
        propertyManagementCid <- UUIDGen.randomUUID[IO]

        // Property: core asset that transfers ownership and accumulates history
        propertyJson =
          s"""{
          "states": {
            "for_sale": { "id": { "value": "for_sale" }, "isFinal": false },
            "under_contract": { "id": { "value": "under_contract" }, "isFinal": false },
            "pending_sale": { "id": { "value": "pending_sale" }, "isFinal": false },
            "owned": { "id": { "value": "owned" }, "isFinal": false },
            "rented": { "id": { "value": "rented" }, "isFinal": false },
            "in_default": { "id": { "value": "in_default" }, "isFinal": false },
            "in_foreclosure": { "id": { "value": "in_foreclosure" }, "isFinal": false },
            "foreclosed": { "id": { "value": "foreclosed" }, "isFinal": false },
            "reo": { "id": { "value": "reo" }, "isFinal": false }
          },
          "initialState": { "value": "for_sale" },
          "transitions": [
            {
              "from": { "value": "for_sale" },
              "to": { "value": "under_contract" },
              "eventName": "accept_offer",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.offerAmount" }, { "var": "state.minPrice" }] },
                  { "===": [{ "var": "machines.${contractCid}.state.status" }, "signed"] }
                ]
              },
              "effect": [
                ["status", "under_contract"],
                ["buyer", { "var": "event.buyerId" }],
                ["contractPrice", { "var": "event.offerAmount" }],
                ["contractDate", { "var": "event.timestamp" }],
                ["saleCount", { "+": [{ "var": "state.saleCount" }, 1] }]
              ],
              "dependencies": ["${contractCid}"]
            },
            {
              "from": { "value": "under_contract" },
              "to": { "value": "for_sale" },
              "eventName": "cancel_contract",
              "guard": {
                "or": [
                  { "===": [{ "var": "machines.${contractCid}.state.status" }, "buyer_default"] },
                  { "===": [{ "var": "machines.${contractCid}.state.status" }, "seller_default"] },
                  { "===": [{ "var": "machines.${contractCid}.state.status" }, "contingency_failed"] }
                ]
              },
              "effect": [
                ["status", "for_sale"],
                ["buyer", null],
                ["contractPrice", null],
                ["failedContracts", { "+": [{ "var": "state.failedContracts" }, 1] }]
              ],
              "dependencies": ["${contractCid}"]
            },
            {
              "from": { "value": "under_contract" },
              "to": { "value": "pending_sale" },
              "eventName": "pass_contingencies",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${inspectionCid}.state.result" }, "passed"] },
                  { "===": [{ "var": "machines.${appraisalCid}.state.result" }, "approved"] },
                  { "===": [{ "var": "machines.${mortgageCid}.state.status" }, "approved"] },
                  { "===": [{ "var": "machines.${escrowCid}.state.status" }, "held"] }
                ]
              },
              "effect": [
                ["status", "pending_sale"],
                ["contingenciesCleared", true],
                ["clearedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionCid}", "${appraisalCid}", "${mortgageCid}", "${escrowCid}"]
            },
            {
              "from": { "value": "pending_sale" },
              "to": { "value": "owned" },
              "eventName": "close_sale",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${titleCid}.state.status" }, "transferred"] },
                  { "===": [{ "var": "machines.${escrowCid}.state.status" }, "closed"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${mortgageCid}",
                    "eventName": "activate",
                    "payload": {
                      "propertyId": { "var": "machineId" },
                      "closingDate": { "var": "event.timestamp" }
                    }
                  }
                ]],
                ["status", "owned"],
                ["owner", { "var": "state.buyer" }],
                ["previousOwner", { "var": "state.owner" }],
                ["purchasePrice", { "var": "state.contractPrice" }],
                ["purchaseDate", { "var": "event.timestamp" }],
                ["hasMortgage", true]
              ],
              "dependencies": ["${titleCid}", "${escrowCid}"]
            },
            {
              "from": { "value": "owned" },
              "to": { "value": "rented" },
              "eventName": "lease_property",
              "guard": {
                "===": [{ "var": "machines.${propertyManagementCid}.state.status" }, "lease_active"]
              },
              "effect": [
                ["status", "rented"],
                ["rentStartDate", { "var": "event.timestamp" }],
                ["isInvestmentProperty", true]
              ],
              "dependencies": ["${propertyManagementCid}"]
            },
            {
              "from": { "value": "rented" },
              "to": { "value": "owned" },
              "eventName": "end_lease",
              "guard": {
                "===": [{ "var": "machines.${propertyManagementCid}.state.status" }, "lease_ended"]
              },
              "effect": [
                ["status", "owned"],
                ["rentEndDate", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${propertyManagementCid}"]
            },
            {
              "from": { "value": "owned" },
              "to": { "value": "in_default" },
              "eventName": "default",
              "guard": {
                "and": [
                  { "===": [{ "var": "state.hasMortgage" }, true] },
                  { "===": [{ "var": "machines.${mortgageCid}.state.status" }, "defaulted"] }
                ]
              },
              "effect": [
                ["status", "in_default"],
                ["defaultDate", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${mortgageCid}"]
            },
            {
              "from": { "value": "rented" },
              "to": { "value": "in_default" },
              "eventName": "default",
              "guard": {
                "and": [
                  { "===": [{ "var": "state.hasMortgage" }, true] },
                  { "===": [{ "var": "machines.${mortgageCid}.state.status" }, "defaulted"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${propertyManagementCid}",
                    "eventName": "terminate_lease",
                    "payload": {
                      "reason": "foreclosure",
                      "terminationDate": { "var": "event.timestamp" }
                    }
                  }
                ]],
                ["status", "in_default"],
                ["defaultDate", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${mortgageCid}"]
            },
            {
              "from": { "value": "in_default" },
              "to": { "value": "owned" },
              "eventName": "cure_default",
              "guard": {
                "===": [{ "var": "machines.${mortgageCid}.state.status" }, "current"]
              },
              "effect": [
                ["status", "owned"],
                ["curedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${mortgageCid}"]
            },
            {
              "from": { "value": "in_default" },
              "to": { "value": "in_foreclosure" },
              "eventName": "foreclose",
              "guard": {
                "===": [{ "var": "machines.${mortgageCid}.state.status" }, "foreclosure"]
              },
              "effect": [
                ["status", "in_foreclosure"],
                ["foreclosureStartDate", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${mortgageCid}"]
            },
            {
              "from": { "value": "in_foreclosure" },
              "to": { "value": "foreclosed" },
              "eventName": "complete_foreclosure",
              "guard": {
                "===": [{ "var": "machines.${mortgageCid}.state.status" }, "foreclosed"]
              },
              "effect": [
                ["_emit", [
                  {
                    "name": "legal_notice",
                    "data": {
                      "noticeType": "foreclosure_complete",
                      "propertyId": { "var": "machineId" },
                      "previousOwner": { "var": "state.owner" },
                      "newOwner": { "var": "machines.${mortgageCid}.state.lender" }
                    }
                  }
                ]],
                ["status", "foreclosed"],
                ["previousOwner", { "var": "state.owner" }],
                ["owner", { "var": "machines.${mortgageCid}.state.lender" }],
                ["foreclosureCompleteDate", { "var": "event.timestamp" }],
                ["hasMortgage", false]
              ],
              "dependencies": ["${mortgageCid}"]
            },
            {
              "from": { "value": "foreclosed" },
              "to": { "value": "reo" },
              "eventName": "bank_owned",
              "guard": true,
              "effect": [
                ["status", "reo"],
                ["reoDate", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reo" },
              "to": { "value": "for_sale" },
              "eventName": "list_for_sale",
              "guard": true,
              "effect": [
                ["status", "for_sale"],
                ["listPrice", { "var": "event.listPrice" }],
                ["listDate", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Purchase Contract: handles offer, acceptance, contingencies
        contractJson =
          s"""{
          "states": {
            "draft": { "id": { "value": "draft" }, "isFinal": false },
            "signed": { "id": { "value": "signed" }, "isFinal": false },
            "contingent": { "id": { "value": "contingent" }, "isFinal": false },
            "contingency_failed": { "id": { "value": "contingency_failed" }, "isFinal": true },
            "buyer_default": { "id": { "value": "buyer_default" }, "isFinal": true },
            "seller_default": { "id": { "value": "seller_default" }, "isFinal": true },
            "executed": { "id": { "value": "executed" }, "isFinal": true }
          },
          "initialState": { "value": "draft" },
          "transitions": [
            {
              "from": { "value": "draft" },
              "to": { "value": "signed" },
              "eventName": "sign",
              "guard": {
                "and": [
                  { "===": [{ "var": "event.buyerSigned" }, true] },
                  { "===": [{ "var": "event.sellerSigned" }, true] }
                ]
              },
              "effect": [
                ["status", "signed"],
                ["signedAt", { "var": "event.timestamp" }],
                ["buyer", { "var": "event.buyer" }],
                ["seller", { "var": "event.seller" }],
                ["purchasePrice", { "var": "event.purchasePrice" }],
                ["earnestMoney", { "var": "event.earnestMoney" }],
                ["closingDate", { "var": "event.closingDate" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "signed" },
              "to": { "value": "contingent" },
              "eventName": "enter_contingency",
              "guard": true,
              "effect": [
                ["status", "contingent"],
                ["contingencyPeriodEnd", { "+": [{ "var": "event.timestamp" }, 2592000] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "contingent" },
              "to": { "value": "contingency_failed" },
              "eventName": "fail_contingency",
              "guard": {
                "or": [
                  { "===": [{ "var": "machines.${inspectionCid}.state.result" }, "failed"] },
                  { "===": [{ "var": "machines.${appraisalCid}.state.result" }, "rejected"] },
                  { "===": [{ "var": "machines.${mortgageCid}.state.status" }, "denied"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${escrowCid}",
                    "eventName": "release_to_buyer",
                    "payload": {
                      "reason": "contingency_failed",
                      "amount": { "var": "state.earnestMoney" }
                    }
                  }
                ]],
                ["status", "contingency_failed"],
                ["failureReason", { "var": "event.reason" }],
                ["failedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionCid}", "${appraisalCid}", "${mortgageCid}"]
            },
            {
              "from": { "value": "contingent" },
              "to": { "value": "buyer_default" },
              "eventName": "buyer_breach",
              "guard": {
                ">": [{ "var": "event.timestamp" }, { "var": "state.closingDate" }]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${escrowCid}",
                    "eventName": "release_to_seller",
                    "payload": {
                      "reason": "buyer_default",
                      "amount": { "var": "state.earnestMoney" }
                    }
                  }
                ]],
                ["status", "buyer_default"],
                ["defaultedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "contingent" },
              "to": { "value": "seller_default" },
              "eventName": "seller_breach",
              "guard": {
                "===": [{ "var": "event.sellerRefusedToClose" }, true]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${escrowCid}",
                    "eventName": "release_to_buyer",
                    "payload": {
                      "reason": "seller_default",
                      "amount": { "*": [{ "var": "state.earnestMoney" }, 2] }
                    }
                  }
                ]],
                ["status", "seller_default"],
                ["defaultedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "contingent" },
              "to": { "value": "executed" },
              "eventName": "close",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${escrowCid}.state.status" }, "closed"] },
                  { "===": [{ "var": "machines.${titleCid}.state.status" }, "transferred"] }
                ]
              },
              "effect": [
                ["status", "executed"],
                ["closedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${escrowCid}", "${titleCid}"]
            }
          ]
        }"""

        // Escrow: holds earnest money and handles disbursement
        escrowJson =
          """{
          "states": {
            "empty": { "id": { "value": "empty" }, "isFinal": false },
            "funded": { "id": { "value": "funded" }, "isFinal": false },
            "held": { "id": { "value": "held" }, "isFinal": false },
            "released_to_buyer": { "id": { "value": "released_to_buyer" }, "isFinal": true },
            "released_to_seller": { "id": { "value": "released_to_seller" }, "isFinal": true },
            "disbursed": { "id": { "value": "disbursed" }, "isFinal": false },
            "closed": { "id": { "value": "closed" }, "isFinal": true }
          },
          "initialState": { "value": "empty" },
          "transitions": [
            {
              "from": { "value": "empty" },
              "to": { "value": "funded" },
              "eventName": "deposit",
              "guard": {
                ">=": [{ "var": "event.amount" }, { "var": "state.requiredAmount" }]
              },
              "effect": [
                ["status", "funded"],
                ["balance", { "var": "event.amount" }],
                ["depositedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "held" },
              "eventName": "hold",
              "guard": true,
              "effect": [
                ["status", "held"],
                ["heldAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "held" },
              "to": { "value": "released_to_buyer" },
              "eventName": "release_to_buyer",
              "guard": true,
              "effect": [
                ["_emit", [
                  {
                    "name": "payment",
                    "data": {
                      "payee": { "var": "state.buyer" },
                      "amount": { "var": "event.amount" },
                      "reason": { "var": "event.reason" }
                    }
                  }
                ]],
                ["status", "released_to_buyer"],
                ["releasedAmount", { "var": "event.amount" }],
                ["releasedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "held" },
              "to": { "value": "released_to_seller" },
              "eventName": "release_to_seller",
              "guard": true,
              "effect": [
                ["_emit", [
                  {
                    "name": "payment",
                    "data": {
                      "payee": { "var": "state.seller" },
                      "amount": { "var": "event.amount" },
                      "reason": { "var": "event.reason" }
                    }
                  }
                ]],
                ["status", "released_to_seller"],
                ["releasedAmount", { "var": "event.amount" }],
                ["releasedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "held" },
              "to": { "value": "disbursed" },
              "eventName": "disburse",
              "guard": true,
              "effect": [
                ["status", "disbursed"],
                ["disbursedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "disbursed" },
              "to": { "value": "closed" },
              "eventName": "close",
              "guard": true,
              "effect": [
                ["status", "closed"],
                ["closedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Inspection: property condition assessment
        inspectionJson =
          """{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "scheduled": { "id": { "value": "scheduled" }, "isFinal": false },
            "completed": { "id": { "value": "completed" }, "isFinal": false },
            "passed": { "id": { "value": "passed" }, "isFinal": true },
            "failed": { "id": { "value": "failed" }, "isFinal": true },
            "passed_with_repairs": { "id": { "value": "passed_with_repairs" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "scheduled" },
              "eventName": "schedule",
              "guard": true,
              "effect": [
                ["status", "scheduled"],
                ["scheduledDate", { "var": "event.inspectionDate" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "scheduled" },
              "to": { "value": "completed" },
              "eventName": "complete",
              "guard": true,
              "effect": [
                ["status", "completed"],
                ["completedAt", { "var": "event.timestamp" }],
                ["issues", { "var": "event.issues" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "completed" },
              "to": { "value": "passed" },
              "eventName": "approve",
              "guard": {
                "===": [{ "var": "state.issues" }, 0]
              },
              "effect": [
                ["result", "passed"],
                ["approvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "completed" },
              "to": { "value": "passed_with_repairs" },
              "eventName": "approve",
              "guard": {
                "and": [
                  { ">": [{ "var": "state.issues" }, 0] },
                  { "<=": [{ "var": "state.issues" }, 3] },
                  { "===": [{ "var": "event.repairsAgreed" }, true] }
                ]
              },
              "effect": [
                ["result", "passed"],
                ["approvedAt", { "var": "event.timestamp" }],
                ["repairsRequired", true]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "completed" },
              "to": { "value": "failed" },
              "eventName": "reject",
              "guard": {
                ">": [{ "var": "state.issues" }, 3]
              },
              "effect": [
                ["result", "failed"],
                ["rejectedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Appraisal: property valuation
        appraisalJson =
          """{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "ordered": { "id": { "value": "ordered" }, "isFinal": false },
            "completed": { "id": { "value": "completed" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": true },
            "rejected": { "id": { "value": "rejected" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "ordered" },
              "eventName": "order",
              "guard": true,
              "effect": [
                ["status", "ordered"],
                ["orderedAt", { "var": "event.timestamp" }],
                ["expectedValue", { "var": "event.purchasePrice" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "ordered" },
              "to": { "value": "completed" },
              "eventName": "complete",
              "guard": true,
              "effect": [
                ["status", "completed"],
                ["appraisedValue", { "var": "event.appraisedValue" }],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "completed" },
              "to": { "value": "approved" },
              "eventName": "review",
              "guard": {
                ">=": [{ "var": "state.appraisedValue" }, { "var": "state.expectedValue" }]
              },
              "effect": [
                ["result", "approved"],
                ["approvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "completed" },
              "to": { "value": "rejected" },
              "eventName": "review",
              "guard": {
                "<": [{ "var": "state.appraisedValue" }, { "var": "state.expectedValue" }]
              },
              "effect": [
                ["result", "rejected"],
                ["rejectedAt", { "var": "event.timestamp" }],
                ["shortfall", { "-": [{ "var": "state.expectedValue" }, { "var": "state.appraisedValue" }] }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Mortgage: loan lifecycle with servicing transfer and foreclosure
        mortgageJson =
          """{
          "states": {
            "application": { "id": { "value": "application" }, "isFinal": false },
            "underwriting": { "id": { "value": "underwriting" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false },
            "denied": { "id": { "value": "denied" }, "isFinal": true },
            "active": { "id": { "value": "active" }, "isFinal": false },
            "current": { "id": { "value": "current" }, "isFinal": false },
            "delinquent_30": { "id": { "value": "delinquent_30" }, "isFinal": false },
            "delinquent_60": { "id": { "value": "delinquent_60" }, "isFinal": false },
            "delinquent_90": { "id": { "value": "delinquent_90" }, "isFinal": false },
            "defaulted": { "id": { "value": "defaulted" }, "isFinal": false },
            "foreclosure": { "id": { "value": "foreclosure" }, "isFinal": false },
            "foreclosed": { "id": { "value": "foreclosed" }, "isFinal": true },
            "paid_off": { "id": { "value": "paid_off" }, "isFinal": true }
          },
          "initialState": { "value": "application" },
          "transitions": [
            {
              "from": { "value": "application" },
              "to": { "value": "underwriting" },
              "eventName": "submit",
              "guard": true,
              "effect": [
                ["status", "underwriting"],
                ["submittedAt", { "var": "event.timestamp" }],
                ["loanAmount", { "var": "event.loanAmount" }],
                ["lender", { "var": "event.lender" }],
                ["borrower", { "var": "event.borrower" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "underwriting" },
              "to": { "value": "approved" },
              "eventName": "underwrite",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.creditScore" }, 620] },
                  { "<=": [{ "var": "event.dti" }, 43] }
                ]
              },
              "effect": [
                ["status", "approved"],
                ["approvedAt", { "var": "event.timestamp" }],
                ["interestRate", { "var": "event.interestRate" }],
                ["term", { "var": "event.term" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "underwriting" },
              "to": { "value": "denied" },
              "eventName": "underwrite",
              "guard": {
                "or": [
                  { "<": [{ "var": "event.creditScore" }, 620] },
                  { ">": [{ "var": "event.dti" }, 43] }
                ]
              },
              "effect": [
                ["status", "denied"],
                ["deniedAt", { "var": "event.timestamp" }],
                ["denialReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "approved" },
              "to": { "value": "active" },
              "eventName": "activate",
              "guard": true,
              "effect": [
                ["status", "active"],
                ["originationDate", { "var": "event.closingDate" }],
                ["servicer", { "var": "state.lender" }],
                ["owner", { "var": "state.lender" }],
                ["principalBalance", { "var": "state.loanAmount" }],
                ["paymentsRemaining", { "var": "state.term" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "active" },
              "to": { "value": "current" },
              "eventName": "first_payment",
              "guard": true,
              "effect": [
                ["status", "current"],
                ["lastPaymentDate", { "var": "event.timestamp" }],
                ["principalBalance", { "-": [{ "var": "state.principalBalance" }, { "var": "event.principalPaid" }] }],
                ["paymentsRemaining", { "-": [{ "var": "state.paymentsRemaining" }, 1] }],
                ["paymentCount", 1]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "current" },
              "to": { "value": "current" },
              "eventName": "make_payment",
              "guard": {
                "<=": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.lastPaymentDate" }] }, 2678400]
              },
              "effect": [
                ["status", "current"],
                ["lastPaymentDate", { "var": "event.timestamp" }],
                ["principalBalance", { "-": [{ "var": "state.principalBalance" }, { "var": "event.principalPaid" }] }],
                ["paymentsRemaining", { "-": [{ "var": "state.paymentsRemaining" }, 1] }],
                ["paymentCount", { "+": [{ "var": "state.paymentCount" }, 1] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "current" },
              "to": { "value": "current" },
              "eventName": "transfer_servicing",
              "guard": true,
              "effect": [
                ["status", "current"],
                ["servicer", { "var": "event.newServicer" }],
                ["servicingTransferDate", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "current" },
              "to": { "value": "current" },
              "eventName": "sell_loan",
              "guard": true,
              "effect": [
                ["status", "current"],
                ["owner", { "var": "event.newOwner" }],
                ["saleDate", { "var": "event.timestamp" }],
                ["soldPrice", { "var": "event.salePrice" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "current" },
              "to": { "value": "delinquent_30" },
              "eventName": "check_payment",
              "guard": {
                "and": [
                  { ">": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.lastPaymentDate" }] }, 2678400] },
                  { "<=": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.lastPaymentDate" }] }, 5270400] }
                ]
              },
              "effect": [
                ["status", "delinquent_30"],
                ["delinquentSince", { "var": "event.timestamp" }],
                ["missedPayments", 1]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "delinquent_30" },
              "to": { "value": "current" },
              "eventName": "make_payment",
              "guard": true,
              "effect": [
                ["status", "current"],
                ["lastPaymentDate", { "var": "event.timestamp" }],
                ["principalBalance", { "-": [{ "var": "state.principalBalance" }, { "var": "event.principalPaid" }] }],
                ["paymentsRemaining", { "-": [{ "var": "state.paymentsRemaining" }, 1] }],
                ["paymentCount", { "+": [{ "var": "state.paymentCount" }, 1] }],
                ["missedPayments", 0]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "delinquent_30" },
              "to": { "value": "delinquent_60" },
              "eventName": "check_payment",
              "guard": {
                "and": [
                  { ">": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.lastPaymentDate" }] }, 5270400] },
                  { "<=": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.lastPaymentDate" }] }, 7862400] }
                ]
              },
              "effect": [
                ["status", "delinquent_60"],
                ["missedPayments", 2]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "delinquent_60" },
              "to": { "value": "current" },
              "eventName": "make_payment",
              "guard": true,
              "effect": [
                ["status", "current"],
                ["lastPaymentDate", { "var": "event.timestamp" }],
                ["principalBalance", { "-": [{ "var": "state.principalBalance" }, { "var": "event.principalPaid" }] }],
                ["paymentsRemaining", { "-": [{ "var": "state.paymentsRemaining" }, 1] }],
                ["paymentCount", { "+": [{ "var": "state.paymentCount" }, 1] }],
                ["missedPayments", 0]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "delinquent_60" },
              "to": { "value": "delinquent_90" },
              "eventName": "check_payment",
              "guard": {
                ">": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.lastPaymentDate" }] }, 7862400]
              },
              "effect": [
                ["status", "delinquent_90"],
                ["missedPayments", 3]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "delinquent_90" },
              "to": { "value": "defaulted" },
              "eventName": "declare_default",
              "guard": {
                ">=": [{ "var": "state.missedPayments" }, 3]
              },
              "effect": [
                ["_emit", [
                  {
                    "name": "notice",
                    "data": {
                      "noticeType": "default",
                      "borrower": { "var": "state.borrower" },
                      "missedPayments": { "var": "state.missedPayments" }
                    }
                  }
                ]],
                ["status", "defaulted"],
                ["defaultDate", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "defaulted" },
              "to": { "value": "current" },
              "eventName": "reinstate",
              "guard": {
                "===": [{ "var": "event.paidPastDue" }, true]
              },
              "effect": [
                ["status", "current"],
                ["lastPaymentDate", { "var": "event.timestamp" }],
                ["reinstatedAt", { "var": "event.timestamp" }],
                ["missedPayments", 0]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "defaulted" },
              "to": { "value": "foreclosure" },
              "eventName": "initiate_foreclosure",
              "guard": {
                ">": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.defaultDate" }] }, 7862400]
              },
              "effect": [
                ["status", "foreclosure"],
                ["foreclosureStartDate", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "foreclosure" },
              "to": { "value": "foreclosed" },
              "eventName": "complete_foreclosure",
              "guard": true,
              "effect": [
                ["status", "foreclosed"],
                ["foreclosureCompleteDate", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "current" },
              "to": { "value": "paid_off" },
              "eventName": "payoff",
              "guard": {
                "<=": [{ "var": "state.principalBalance" }, 0]
              },
              "effect": [
                ["status", "paid_off"],
                ["paidOffAt", { "var": "event.timestamp" }],
                ["finalPaymentAmount", { "var": "event.payoffAmount" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Title: ownership transfer and recording
        titleJson =
          """{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "searching": { "id": { "value": "searching" }, "isFinal": false },
            "clear": { "id": { "value": "clear" }, "isFinal": false },
            "issues_found": { "id": { "value": "issues_found" }, "isFinal": false },
            "insured": { "id": { "value": "insured" }, "isFinal": false },
            "transferred": { "id": { "value": "transferred" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "searching" },
              "eventName": "search",
              "guard": true,
              "effect": [
                ["status", "searching"],
                ["searchStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "searching" },
              "to": { "value": "clear" },
              "eventName": "complete_search",
              "guard": {
                "===": [{ "var": "event.issuesFound" }, 0]
              },
              "effect": [
                ["status", "clear"],
                ["clearAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "searching" },
              "to": { "value": "issues_found" },
              "eventName": "complete_search",
              "guard": {
                ">": [{ "var": "event.issuesFound" }, 0]
              },
              "effect": [
                ["status", "issues_found"],
                ["issues", { "var": "event.issuesFound" }],
                ["foundAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "issues_found" },
              "to": { "value": "clear" },
              "eventName": "resolve_issues",
              "guard": true,
              "effect": [
                ["status", "clear"],
                ["resolvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "clear" },
              "to": { "value": "insured" },
              "eventName": "insure",
              "guard": true,
              "effect": [
                ["status", "insured"],
                ["insuredAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "insured" },
              "to": { "value": "transferred" },
              "eventName": "transfer",
              "guard": true,
              "effect": [
                ["status", "transferred"],
                ["transferredAt", { "var": "event.timestamp" }],
                ["fromOwner", { "var": "event.fromOwner" }],
                ["toOwner", { "var": "event.toOwner" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Property Management: rental operations
        propertyManagementJson =
          """{
          "states": {
            "available": { "id": { "value": "available" }, "isFinal": false },
            "showing": { "id": { "value": "showing" }, "isFinal": false },
            "lease_pending": { "id": { "value": "lease_pending" }, "isFinal": false },
            "lease_active": { "id": { "value": "lease_active" }, "isFinal": false },
            "lease_ended": { "id": { "value": "lease_ended" }, "isFinal": false },
            "eviction": { "id": { "value": "eviction" }, "isFinal": false },
            "terminated": { "id": { "value": "terminated" }, "isFinal": true }
          },
          "initialState": { "value": "available" },
          "transitions": [
            {
              "from": { "value": "available" },
              "to": { "value": "showing" },
              "eventName": "schedule_showing",
              "guard": true,
              "effect": [
                ["status", "showing"],
                ["showingCount", { "+": [{ "var": "state.showingCount" }, 1] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "showing" },
              "to": { "value": "lease_pending" },
              "eventName": "accept_application",
              "guard": {
                ">=": [{ "var": "event.creditScore" }, 650]
              },
              "effect": [
                ["status", "lease_pending"],
                ["tenant", { "var": "event.tenant" }],
                ["monthlyRent", { "var": "event.monthlyRent" }],
                ["leaseTermMonths", { "var": "event.leaseTermMonths" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "lease_pending" },
              "to": { "value": "lease_active" },
              "eventName": "sign_lease",
              "guard": true,
              "effect": [
                ["status", "lease_active"],
                ["leaseStartDate", { "var": "event.timestamp" }],
                ["leaseEndDate", { "+": [{ "var": "event.timestamp" }, { "*": [{ "var": "state.leaseTermMonths" }, 2592000] }] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "lease_active" },
              "to": { "value": "lease_ended" },
              "eventName": "end_lease",
              "guard": {
                ">=": [{ "var": "event.timestamp" }, { "var": "state.leaseEndDate" }]
              },
              "effect": [
                ["status", "lease_ended"],
                ["leaseEndedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "lease_active" },
              "to": { "value": "eviction" },
              "eventName": "evict",
              "guard": {
                ">=": [{ "var": "event.missedPayments" }, 3]
              },
              "effect": [
                ["status", "eviction"],
                ["evictionStartDate", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "lease_active" },
              "to": { "value": "terminated" },
              "eventName": "terminate_lease",
              "guard": {
                "===": [{ "var": "event.reason" }, "foreclosure"]
              },
              "effect": [
                ["status", "terminated"],
                ["terminationReason", { "var": "event.reason" }],
                ["terminatedAt", { "var": "event.terminationDate" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "eviction" },
              "to": { "value": "available" },
              "eventName": "complete_eviction",
              "guard": true,
              "effect": [
                ["status", "available"],
                ["evictionCompletedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "lease_ended" },
              "to": { "value": "available" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["status", "available"],
                ["showingCount", 0]
              ],
              "dependencies": []
            }
          ]
        }"""

        propertyDef <- IO.fromEither(
          decode[StateMachineDefinition](propertyJson).left.map(err =>
            new RuntimeException(s"Failed to decode property JSON: $err")
          )
        )

        contractDef <- IO.fromEither(
          decode[StateMachineDefinition](contractJson).left.map(err =>
            new RuntimeException(s"Failed to decode contract JSON: $err")
          )
        )

        escrowDef <- IO.fromEither(
          decode[StateMachineDefinition](escrowJson).left.map(err =>
            new RuntimeException(s"Failed to decode escrow JSON: $err")
          )
        )

        inspectionDef <- IO.fromEither(
          decode[StateMachineDefinition](inspectionJson).left.map(err =>
            new RuntimeException(s"Failed to decode inspection JSON: $err")
          )
        )

        appraisalDef <- IO.fromEither(
          decode[StateMachineDefinition](appraisalJson).left.map(err =>
            new RuntimeException(s"Failed to decode appraisal JSON: $err")
          )
        )

        mortgageDef <- IO.fromEither(
          decode[StateMachineDefinition](mortgageJson).left.map(err =>
            new RuntimeException(s"Failed to decode mortgage JSON: $err")
          )
        )

        titleDef <- IO.fromEither(
          decode[StateMachineDefinition](titleJson).left.map(err =>
            new RuntimeException(s"Failed to decode title JSON: $err")
          )
        )

        propertyManagementDef <- IO.fromEither(
          decode[StateMachineDefinition](propertyManagementJson).left.map(err =>
            new RuntimeException(s"Failed to decode property management JSON: $err")
          )
        )

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        propertyData = MapValue(
          Map(
            "address"         -> StrValue("123 Main St"),
            "owner"           -> StrValue(aliceAddr.toString),
            "listPrice"       -> IntValue(500000),
            "minPrice"        -> IntValue(480000),
            "status"          -> StrValue("for_sale"),
            "saleCount"       -> IntValue(0),
            "failedContracts" -> IntValue(0)
          )
        )
        propertyHash <- (propertyData: JsonLogicValue).computeDigest

        contractData = MapValue(
          Map(
            "status" -> StrValue("draft")
          )
        )
        contractHash <- (contractData: JsonLogicValue).computeDigest

        escrowData = MapValue(
          Map(
            "status"         -> StrValue("empty"),
            "requiredAmount" -> IntValue(10000),
            "buyer"          -> StrValue(bobAddr.toString),
            "seller"         -> StrValue(aliceAddr.toString)
          )
        )
        escrowHash <- (escrowData: JsonLogicValue).computeDigest

        inspectionData = MapValue(
          Map(
            "status" -> StrValue("pending")
          )
        )
        inspectionHash <- (inspectionData: JsonLogicValue).computeDigest

        appraisalData = MapValue(
          Map(
            "status" -> StrValue("pending")
          )
        )
        appraisalHash <- (appraisalData: JsonLogicValue).computeDigest

        mortgageData = MapValue(
          Map(
            "status" -> StrValue("application")
          )
        )
        mortgageHash <- (mortgageData: JsonLogicValue).computeDigest

        titleData = MapValue(
          Map(
            "status" -> StrValue("pending")
          )
        )
        titleHash <- (titleData: JsonLogicValue).computeDigest

        propertyManagementData = MapValue(
          Map(
            "status"       -> StrValue("available"),
            "showingCount" -> IntValue(0)
          )
        )
        propertyManagementHash <- (propertyManagementData: JsonLogicValue).computeDigest

        propertyFiber = Records.StateMachineFiberRecord(
          cid = propertyCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = propertyDef,
          currentState = StateId("for_sale"),
          stateData = propertyData,
          stateDataHash = propertyHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        contractFiber = Records.StateMachineFiberRecord(
          cid = contractCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = contractDef,
          currentState = StateId("draft"),
          stateData = contractData,
          stateDataHash = contractHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(registry.addresses),
          status = FiberStatus.Active
        )

        escrowFiber = Records.StateMachineFiberRecord(
          cid = escrowCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = escrowDef,
          currentState = StateId("empty"),
          stateData = escrowData,
          stateDataHash = escrowHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Charlie).map(registry.addresses),
          status = FiberStatus.Active
        )

        inspectionFiber = Records.StateMachineFiberRecord(
          cid = inspectionCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = inspectionDef,
          currentState = StateId("pending"),
          stateData = inspectionData,
          stateDataHash = inspectionHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Dave).map(registry.addresses),
          status = FiberStatus.Active
        )

        appraisalFiber = Records.StateMachineFiberRecord(
          cid = appraisalCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = appraisalDef,
          currentState = StateId("pending"),
          stateData = appraisalData,
          stateDataHash = appraisalHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Eve).map(registry.addresses),
          status = FiberStatus.Active
        )

        mortgageFiber = Records.StateMachineFiberRecord(
          cid = mortgageCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = mortgageDef,
          currentState = StateId("application"),
          stateData = mortgageData,
          stateDataHash = mortgageHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Faythe).map(registry.addresses),
          status = FiberStatus.Active
        )

        titleFiber = Records.StateMachineFiberRecord(
          cid = titleCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = titleDef,
          currentState = StateId("pending"),
          stateData = titleData,
          stateDataHash = titleHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Grace).map(registry.addresses),
          status = FiberStatus.Active
        )

        propertyManagementFiber = Records.StateMachineFiberRecord(
          cid = propertyManagementCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = propertyManagementDef,
          currentState = StateId("available"),
          stateData = propertyManagementData,
          stateDataHash = propertyManagementHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Heidi).map(registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            propertyCid           -> propertyFiber,
            contractCid           -> contractFiber,
            escrowCid             -> escrowFiber,
            inspectionCid         -> inspectionFiber,
            appraisalCid          -> appraisalFiber,
            mortgageCid           -> mortgageFiber,
            titleCid              -> titleFiber,
            propertyManagementCid -> propertyManagementFiber
          )
        )

        // PHASE 1: CONTRACT PHASE
        // Step 1: Sign contract
        signContractUpdate = Updates.TransitionStateMachine(
          contractCid,
          "sign",
          MapValue(
            Map(
              "timestamp"     -> IntValue(1000),
              "buyerSigned"   -> BoolValue(true),
              "sellerSigned"  -> BoolValue(true),
              "buyer"         -> StrValue(bobAddr.toString),
              "seller"        -> StrValue(aliceAddr.toString),
              "purchasePrice" -> IntValue(500000),
              "earnestMoney"  -> IntValue(10000),
              "closingDate"   -> IntValue(3000)
            )
          ),
          FiberOrdinal.MinValue
        )
        signContractProof <- registry.generateProofs(signContractUpdate, Set(Alice, Bob))
        state1            <- combiner.insert(inState, Signed(signContractUpdate, signContractProof))

        // Step 2: Accept offer on property
        acceptOfferUpdate = Updates.TransitionStateMachine(
          propertyCid,
          "accept_offer",
          MapValue(
            Map(
              "timestamp"   -> IntValue(1100),
              "offerAmount" -> IntValue(500000),
              "buyerId"     -> StrValue(bobAddr.toString)
            )
          ),
          FiberOrdinal.MinValue
        )
        acceptOfferProof <- registry.generateProofs(acceptOfferUpdate, Set(Alice))
        state2           <- combiner.insert(state1, Signed(acceptOfferUpdate, acceptOfferProof))

        // Step 3: Deposit earnest money
        depositUpdate = Updates.TransitionStateMachine(
          escrowCid,
          "deposit",
          MapValue(
            Map(
              "timestamp" -> IntValue(1200),
              "amount"    -> IntValue(10000)
            )
          ),
          FiberOrdinal.MinValue
        )
        depositProof <- registry.generateProofs(depositUpdate, Set(Bob))
        state3       <- combiner.insert(state2, Signed(depositUpdate, depositProof))

        // Step 4: Hold escrow
        holdEscrowSeqNum = state3.calculated.stateMachines(escrowCid).sequenceNumber
        holdEscrowUpdate = Updates.TransitionStateMachine(
          escrowCid,
          "hold",
          MapValue(Map("timestamp" -> IntValue(1300))),
          holdEscrowSeqNum
        )
        holdEscrowProof <- registry.generateProofs(holdEscrowUpdate, Set(Charlie))
        state4          <- combiner.insert(state3, Signed(holdEscrowUpdate, holdEscrowProof))

        // Step 5: Enter contingency period
        enterContingencySeqNum = state4.calculated.stateMachines(contractCid).sequenceNumber
        enterContingencyUpdate = Updates.TransitionStateMachine(
          contractCid,
          "enter_contingency",
          MapValue(Map("timestamp" -> IntValue(1400))),
          enterContingencySeqNum
        )
        enterContingencyProof <- registry.generateProofs(enterContingencyUpdate, Set(Alice, Bob))
        state5                <- combiner.insert(state4, Signed(enterContingencyUpdate, enterContingencyProof))

        // PHASE 2: CONTINGENCY PHASE
        // Step 6: Schedule and complete inspection
        scheduleInspectionUpdate = Updates.TransitionStateMachine(
          inspectionCid,
          "schedule",
          MapValue(Map("inspectionDate" -> IntValue(1500))),
          FiberOrdinal.MinValue
        )
        scheduleInspectionProof <- registry.generateProofs(scheduleInspectionUpdate, Set(Dave))
        state6                  <- combiner.insert(state5, Signed(scheduleInspectionUpdate, scheduleInspectionProof))

        completeInspectionSeqNum = state6.calculated.stateMachines(inspectionCid).sequenceNumber
        completeInspectionUpdate = Updates.TransitionStateMachine(
          inspectionCid,
          "complete",
          MapValue(
            Map(
              "timestamp" -> IntValue(1600),
              "issues"    -> IntValue(1)
            )
          ),
          completeInspectionSeqNum
        )
        completeInspectionProof <- registry.generateProofs(completeInspectionUpdate, Set(Dave))
        state7                  <- combiner.insert(state6, Signed(completeInspectionUpdate, completeInspectionProof))

        approveInspectionSeqNum = state7.calculated.stateMachines(inspectionCid).sequenceNumber
        approveInspectionUpdate = Updates.TransitionStateMachine(
          inspectionCid,
          "approve",
          MapValue(
            Map(
              "timestamp"     -> IntValue(1700),
              "repairsAgreed" -> BoolValue(true)
            )
          ),
          approveInspectionSeqNum
        )
        approveInspectionProof <- registry.generateProofs(approveInspectionUpdate, Set(Dave))
        state8                 <- combiner.insert(state7, Signed(approveInspectionUpdate, approveInspectionProof))

        // Step 7: Order and complete appraisal
        orderAppraisalUpdate = Updates.TransitionStateMachine(
          appraisalCid,
          "order",
          MapValue(
            Map(
              "timestamp"     -> IntValue(1800),
              "purchasePrice" -> IntValue(500000)
            )
          ),
          FiberOrdinal.MinValue
        )
        orderAppraisalProof <- registry.generateProofs(orderAppraisalUpdate, Set(Eve))
        state9              <- combiner.insert(state8, Signed(orderAppraisalUpdate, orderAppraisalProof))

        completeAppraisalSeqNum = state9.calculated.stateMachines(appraisalCid).sequenceNumber
        completeAppraisalUpdate = Updates.TransitionStateMachine(
          appraisalCid,
          "complete",
          MapValue(
            Map(
              "timestamp"      -> IntValue(1900),
              "appraisedValue" -> IntValue(510000)
            )
          ),
          completeAppraisalSeqNum
        )
        completeAppraisalProof <- registry.generateProofs(completeAppraisalUpdate, Set(Eve))
        state10                <- combiner.insert(state9, Signed(completeAppraisalUpdate, completeAppraisalProof))

        reviewAppraisalSeqNum = state10.calculated.stateMachines(appraisalCid).sequenceNumber
        reviewAppraisalUpdate = Updates.TransitionStateMachine(
          appraisalCid,
          "review",
          MapValue(Map("timestamp" -> IntValue(2000))),
          reviewAppraisalSeqNum
        )
        reviewAppraisalProof <- registry.generateProofs(reviewAppraisalUpdate, Set(Eve))
        state11              <- combiner.insert(state10, Signed(reviewAppraisalUpdate, reviewAppraisalProof))

        // Step 8: Submit and approve mortgage
        submitMortgageUpdate = Updates.TransitionStateMachine(
          mortgageCid,
          "submit",
          MapValue(
            Map(
              "timestamp"  -> IntValue(2100),
              "loanAmount" -> IntValue(400000),
              "lender"     -> StrValue(registry.addresses(Faythe).toString),
              "borrower"   -> StrValue(bobAddr.toString)
            )
          ),
          FiberOrdinal.MinValue
        )
        submitMortgageProof <- registry.generateProofs(submitMortgageUpdate, Set(Faythe))
        state12             <- combiner.insert(state11, Signed(submitMortgageUpdate, submitMortgageProof))

        underwriteMortgageSeqNum = state12.calculated.stateMachines(mortgageCid).sequenceNumber
        underwriteMortgageUpdate = Updates.TransitionStateMachine(
          mortgageCid,
          "underwrite",
          MapValue(
            Map(
              "timestamp"    -> IntValue(2200),
              "creditScore"  -> IntValue(720),
              "dti"          -> IntValue(35),
              "interestRate" -> IntValue(4),
              "term"         -> IntValue(360)
            )
          ),
          underwriteMortgageSeqNum
        )
        underwriteMortgageProof <- registry.generateProofs(underwriteMortgageUpdate, Set(Faythe))
        state13                 <- combiner.insert(state12, Signed(underwriteMortgageUpdate, underwriteMortgageProof))

        // Step 9: Pass all contingencies
        passContingenciesSeqNum = state13.calculated.stateMachines(propertyCid).sequenceNumber
        passContingenciesUpdate = Updates.TransitionStateMachine(
          propertyCid,
          "pass_contingencies",
          MapValue(Map("timestamp" -> IntValue(2300))),
          passContingenciesSeqNum
        )
        passContingenciesProof <- registry.generateProofs(passContingenciesUpdate, Set(Alice))
        state14                <- combiner.insert(state13, Signed(passContingenciesUpdate, passContingenciesProof))

        // PHASE 3: CLOSING PHASE
        // Step 10: Title search and transfer
        searchTitleUpdate = Updates.TransitionStateMachine(
          titleCid,
          "search",
          MapValue(Map("timestamp" -> IntValue(2400))),
          FiberOrdinal.MinValue
        )
        searchTitleProof <- registry.generateProofs(searchTitleUpdate, Set(Grace))
        state15          <- combiner.insert(state14, Signed(searchTitleUpdate, searchTitleProof))

        completeSearchSeqNum = state15.calculated.stateMachines(titleCid).sequenceNumber
        completeSearchUpdate = Updates.TransitionStateMachine(
          titleCid,
          "complete_search",
          MapValue(
            Map(
              "timestamp"   -> IntValue(2500),
              "issuesFound" -> IntValue(0)
            )
          ),
          completeSearchSeqNum
        )
        completeSearchProof <- registry.generateProofs(completeSearchUpdate, Set(Grace))
        state16             <- combiner.insert(state15, Signed(completeSearchUpdate, completeSearchProof))

        insureTitleSeqNum = state16.calculated.stateMachines(titleCid).sequenceNumber
        insureTitleUpdate = Updates.TransitionStateMachine(
          titleCid,
          "insure",
          MapValue(Map("timestamp" -> IntValue(2600))),
          insureTitleSeqNum
        )
        insureTitleProof <- registry.generateProofs(insureTitleUpdate, Set(Grace))
        state17          <- combiner.insert(state16, Signed(insureTitleUpdate, insureTitleProof))

        transferTitleSeqNum = state17.calculated.stateMachines(titleCid).sequenceNumber
        transferTitleUpdate = Updates.TransitionStateMachine(
          titleCid,
          "transfer",
          MapValue(
            Map(
              "timestamp" -> IntValue(2700),
              "fromOwner" -> StrValue(aliceAddr.toString),
              "toOwner"   -> StrValue(bobAddr.toString)
            )
          ),
          transferTitleSeqNum
        )
        transferTitleProof <- registry.generateProofs(transferTitleUpdate, Set(Grace))
        state18            <- combiner.insert(state17, Signed(transferTitleUpdate, transferTitleProof))

        // Step 11: Disburse and close escrow
        disburseEscrowSeqNum = state18.calculated.stateMachines(escrowCid).sequenceNumber
        disburseEscrowUpdate = Updates.TransitionStateMachine(
          escrowCid,
          "disburse",
          MapValue(Map("timestamp" -> IntValue(2800))),
          disburseEscrowSeqNum
        )
        disburseEscrowProof <- registry.generateProofs(disburseEscrowUpdate, Set(Charlie))
        state19             <- combiner.insert(state18, Signed(disburseEscrowUpdate, disburseEscrowProof))

        closeEscrowSeqNum = state19.calculated.stateMachines(escrowCid).sequenceNumber
        closeEscrowUpdate = Updates.TransitionStateMachine(
          escrowCid,
          "close",
          MapValue(Map("timestamp" -> IntValue(2900))),
          closeEscrowSeqNum
        )
        closeEscrowProof <- registry.generateProofs(closeEscrowUpdate, Set(Charlie))
        state20          <- combiner.insert(state19, Signed(closeEscrowUpdate, closeEscrowProof))

        // Step 12: Close sale on property (triggers mortgage activation)
        closeSaleSeqNum = state20.calculated.stateMachines(propertyCid).sequenceNumber
        closeSaleUpdate = Updates.TransitionStateMachine(
          propertyCid,
          "close_sale",
          MapValue(Map("timestamp" -> IntValue(3000))),
          closeSaleSeqNum
        )
        closeSaleProof <- registry.generateProofs(closeSaleUpdate, Set(Alice))
        state21        <- combiner.insert(state20, Signed(closeSaleUpdate, closeSaleProof))

        // Verify mortgage was activated by trigger
        mortgageAfterClose = state21.calculated.stateMachines
          .get(mortgageCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        mortgageStatus: Option[String] = mortgageAfterClose.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        // Step 13: Close contract
        closeContractSeqNum = state21.calculated.stateMachines(contractCid).sequenceNumber
        closeContractUpdate = Updates.TransitionStateMachine(
          contractCid,
          "close",
          MapValue(Map("timestamp" -> IntValue(3100))),
          closeContractSeqNum
        )
        closeContractProof <- registry.generateProofs(closeContractUpdate, Set(Alice, Bob))
        state22            <- combiner.insert(state21, Signed(closeContractUpdate, closeContractProof))

        // PHASE 4: OWNERSHIP PHASE - Make first mortgage payment
        firstPaymentSeqNum = state22.calculated.stateMachines(mortgageCid).sequenceNumber
        firstPaymentUpdate = Updates.TransitionStateMachine(
          mortgageCid,
          "first_payment",
          MapValue(
            Map(
              "timestamp"     -> IntValue(5000),
              "principalPaid" -> IntValue(500)
            )
          ),
          firstPaymentSeqNum
        )
        firstPaymentProof <- registry.generateProofs(firstPaymentUpdate, Set(Bob))
        state23           <- combiner.insert(state22, Signed(firstPaymentUpdate, firstPaymentProof))

        mortgageAfterFirstPayment = state23.calculated.stateMachines
          .get(mortgageCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        propertyAfterSale = state23.calculated.stateMachines
          .get(propertyCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        propertyOwner: Option[String] = propertyAfterSale.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("owner").collect { case StrValue(o) => o }
            case _           => None
          }
        }

        propertyStatus: Option[String] = propertyAfterSale.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _           => None
          }
        }

        mortgageBalance: Option[BigInt] = mortgageAfterFirstPayment.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("principalBalance").collect { case IntValue(b) => b }
            case _           => None
          }
        }

      } yield expect.all(
        // Verify contract signed
        state1.calculated.stateMachines.get(contractCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("signed")
          case _                                  => false
        },
        // Verify property under contract
        state2.calculated.stateMachines.get(propertyCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("under_contract")
          case _                                  => false
        },
        // Verify escrow funded
        state3.calculated.stateMachines.get(escrowCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("funded")
          case _                                  => false
        },
        // Verify contract in contingency
        state5.calculated.stateMachines.get(contractCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("contingent")
          case _                                  => false
        },
        // Verify inspection passed with repairs
        state8.calculated.stateMachines.get(inspectionCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("passed_with_repairs")
          case _                                  => false
        },
        // Verify appraisal approved
        state11.calculated.stateMachines.get(appraisalCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("approved")
          case _                                  => false
        },
        // Verify mortgage approved
        state13.calculated.stateMachines.get(mortgageCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("approved")
          case _                                  => false
        },
        // Verify property pending sale
        state14.calculated.stateMachines.get(propertyCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("pending_sale")
          case _                                  => false
        },
        // Verify title transferred
        state18.calculated.stateMachines.get(titleCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("transferred")
          case _                                  => false
        },
        // Verify escrow closed
        state20.calculated.stateMachines.get(escrowCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("closed")
          case _                                  => false
        },
        // Verify property ownership transferred and now owned by Bob
        propertyAfterSale.isDefined,
        propertyAfterSale.map(_.currentState).contains(StateId("owned")),
        propertyOwner.contains(bobAddr.toString),
        propertyStatus.contains("owned"),
        // Verify mortgage activated by trigger from property close
        mortgageAfterClose.isDefined,
        mortgageStatus.contains("active"),
        // Verify mortgage is now current after first payment
        mortgageAfterFirstPayment.isDefined,
        mortgageAfterFirstPayment.map(_.currentState).contains(StateId("current")),
        mortgageBalance.contains(BigInt(399500)), // 400000 - 500
        // Verify contract executed
        state22.calculated.stateMachines.get(contractCid).exists {
          case r: Records.StateMachineFiberRecord => r.currentState == StateId("executed")
          case _                                  => false
        }
      )
    }
  }
}
