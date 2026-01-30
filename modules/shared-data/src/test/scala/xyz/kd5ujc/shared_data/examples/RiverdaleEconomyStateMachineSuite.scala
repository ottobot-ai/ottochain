package xyz.kd5ujc.shared_data.examples

import java.util.UUID

import cats.effect.std.UUIDGen
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Mock.MockL0NodeContext
import xyz.kd5ujc.shared_test.Participant._

import io.circe.parser._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/**
 * Riverdale Economy: Complex State Machine Test
 *
 * This test demonstrates a complete economic ecosystem with:
 * - 26 participants across 6 sectors
 * - Cross-machine triggers and cascading state changes
 * - Parent-child spawning for dynamic machine creation
 * - Complex dependency graphs with 50+ relationships
 * - Multi-layered business logic
 * - Crisis scenarios with system-wide impacts
 * - 60+ sequential events
 */
object RiverdaleEconomyStateMachineSuite extends SimpleIOSuite with Checkers {

  private val securityProviderResource: Resource[IO, SecurityProvider[IO]] = SecurityProvider.forAsync[IO]

  // Helper function to decode JSON to StateMachineDefinition
  private def decodeStateMachine(json: String): IO[StateMachineDefinition] =
    IO.fromEither(
      decode[StateMachineDefinition](json).left.map(err =>
        new RuntimeException(s"Failed to decode state machine JSON: $err")
      )
    )

  // JSON-encoded state machine definitions for each sector
  def manufacturerStateMachineJson(): String =
    s"""{
      "states": {
        "idle": { "id": { "value": "idle" }, "isFinal": false },
        "production_scheduled": { "id": { "value": "production_scheduled" }, "isFinal": false },
        "producing": { "id": { "value": "producing" }, "isFinal": false },
        "inventory_full": { "id": { "value": "inventory_full" }, "isFinal": false },
        "shipping": { "id": { "value": "shipping" }, "isFinal": false },
        "supply_shortage": { "id": { "value": "supply_shortage" }, "isFinal": false }
      },
      "initialState": { "value": "idle" },
      "transitions": [
        {
          "from": { "value": "idle" },
          "to": { "value": "production_scheduled" },
          "eventName": "schedule_production",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] },
              { "<": [{ "var": "state.inventory" }, { "var": "state.maxInventory" }] }
            ]
          },
          "effect": [
            ["status", "production_scheduled"],
            ["scheduledAt", { "var": "event.timestamp" }],
            ["batchSize", { "var": "event.batchSize" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "production_scheduled" },
          "to": { "value": "producing" },
          "eventName": "start_production",
          "guard": true,
          "effect": [
            ["status", "producing"],
            ["productionStartedAt", { "var": "event.timestamp" }],
            ["rawMaterials", { "-": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "producing" },
          "to": { "value": "inventory_full" },
          "eventName": "complete_batch",
          "guard": { ">=": [{ "+": [{ "var": "state.inventory" }, { "var": "state.batchSize" }] }, { "var": "state.maxInventory" }] },
          "effect": [
            ["status", "inventory_full"],
            ["inventory", { "+": [{ "var": "state.inventory" }, { "var": "state.batchSize" }] }],
            ["completedAt", { "var": "event.timestamp" }],
            ["totalProduced", { "+": [{ "var": "state.totalProduced" }, { "var": "state.batchSize" }] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "inventory_full" },
          "to": { "value": "shipping" },
          "eventName": "fulfill_order",
          "guard": { ">=": [{ "var": "state.inventory" }, { "var": "event.orderQuantity" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.retailerId" },
                "eventName": "receive_shipment",
                "payload": {
                  "quantity": { "var": "event.orderQuantity" },
                  "supplier": { "var": "machineId" },
                  "shipmentId": { "var": "event.shipmentId" }
                }
              }
            ],
            "status": "shipping",
            "inventory": { "-": [{ "var": "state.inventory" }, { "var": "event.orderQuantity" }] },
            "shipmentCount": { "+": [{ "var": "state.shipmentCount" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "shipping" },
          "to": { "value": "idle" },
          "eventName": "complete_shipment",
          "guard": true,
          "effect": [
            ["status", "idle"],
            ["lastShipmentCompletedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "shipping" },
          "to": { "value": "production_scheduled" },
          "eventName": "schedule_production",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] },
              { "<": [{ "var": "state.inventory" }, { "var": "state.maxInventory" }] }
            ]
          },
          "effect": [
            ["status", "production_scheduled"],
            ["scheduledAt", { "var": "event.timestamp" }],
            ["batchSize", { "var": "event.batchSize" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "idle" },
          "to": { "value": "supply_shortage" },
          "eventName": "check_materials",
          "guard": { "<": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] },
          "effect": {
            "_emit": [
              {
                "name": "alert",
                "data": {
                  "severity": "high",
                  "message": "Raw material shortage detected",
                  "manufacturer": { "var": "machineId" }
                }
              }
            ],
            "status": "supply_shortage",
            "shortageDetectedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "idle" },
          "to": { "value": "idle" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "inventory_full" },
          "to": { "value": "inventory_full" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "shipping" },
          "to": { "value": "shipping" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "supply_shortage" },
          "to": { "value": "supply_shortage" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        }
      ]
    }"""

  def retailerStateMachineJson(supplierCid: String, paymentProcessorCid: String): String =
    s"""{
      "states": {
        "open": { "id": { "value": "open" }, "isFinal": false },
        "stocking": { "id": { "value": "stocking" }, "isFinal": false },
        "sale_active": { "id": { "value": "sale_active" }, "isFinal": false },
        "inventory_low": { "id": { "value": "inventory_low" }, "isFinal": false },
        "closed": { "id": { "value": "closed" }, "isFinal": false },
        "holiday_hours": { "id": { "value": "holiday_hours" }, "isFinal": false }
      },
      "initialState": { "value": "open" },
      "transitions": [
        {
          "from": { "value": "open" },
          "to": { "value": "inventory_low" },
          "eventName": "check_inventory",
          "guard": { "<": [{ "var": "state.stock" }, { "var": "state.reorderThreshold" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": "$supplierCid",
                "eventName": "fulfill_order",
                "payload": {
                  "retailerId": { "var": "machineId" },
                  "orderQuantity": { "var": "state.reorderQuantity" },
                  "shipmentId": { "var": "event.shipmentId" }
                }
              }
            ],
            "status": "inventory_low",
            "reorderInitiatedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "inventory_low" },
          "to": { "value": "stocking" },
          "eventName": "receive_shipment",
          "guard": { "===": [{ "var": "event.supplier" }, "$supplierCid"] },
          "effect": [
            ["status", "stocking"],
            ["stock", { "+": [{ "var": "state.stock" }, { "var": "event.quantity" }] }],
            ["lastRestockAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "stocking" },
          "to": { "value": "open" },
          "eventName": "reopen",
          "guard": true,
          "effect": [
            ["status", "open"],
            ["reopenedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "open" },
          "to": { "value": "sale_active" },
          "eventName": "start_sale",
          "guard": {
            "or": [
              { "===": [{ "var": "event.season" }, "holiday"] },
              { "===": [{ "var": "event.season" }, "black_friday"] }
            ]
          },
          "effect": [
            ["status", "sale_active"],
            ["saleStartedAt", { "var": "event.timestamp" }],
            ["discountRate", { "var": "event.discountRate" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "open" },
          "to": { "value": "open" },
          "eventName": "process_sale",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.stock" }, { "var": "event.quantity" }] },
              { "===": [{ "var": "machines.$paymentProcessorCid.state.status" }, "active"] }
            ]
          },
          "effect": [
            ["stock", { "-": [{ "var": "state.stock" }, { "var": "event.quantity" }] }],
            ["revenue", { "+": [
              { "var": "state.revenue" },
              { "*": [{ "var": "event.quantity" }, { "var": "state.pricePerUnit" }] }
            ] }],
            ["salesCount", { "+": [{ "var": "state.salesCount" }, 1] }]
          ],
          "dependencies": ["$paymentProcessorCid"]
        },
        {
          "from": { "value": "stocking" },
          "to": { "value": "stocking" },
          "eventName": "process_sale",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.stock" }, { "var": "event.quantity" }] },
              { "===": [{ "var": "machines.$paymentProcessorCid.state.status" }, "active"] }
            ]
          },
          "effect": [
            ["stock", { "-": [{ "var": "state.stock" }, { "var": "event.quantity" }] }],
            ["revenue", { "+": [
              { "var": "state.revenue" },
              { "*": [{ "var": "event.quantity" }, { "var": "state.pricePerUnit" }] }
            ] }],
            ["salesCount", { "+": [{ "var": "state.salesCount" }, 1] }]
          ],
          "dependencies": ["$paymentProcessorCid"]
        },
        {
          "from": { "value": "open" },
          "to": { "value": "open" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "stocking" },
          "to": { "value": "stocking" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "sale_active" },
          "to": { "value": "sale_active" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        }
      ]
    }"""

  def bankStateMachineJson(federalReserveCid: String): String =
    s"""{
      "states": {
        "operating": { "id": { "value": "operating" }, "isFinal": false },
        "processing_loan": { "id": { "value": "processing_loan" }, "isFinal": false },
        "loan_approved": { "id": { "value": "loan_approved" }, "isFinal": false },
        "loan_denied": { "id": { "value": "loan_denied" }, "isFinal": false },
        "loan_servicing": { "id": { "value": "loan_servicing" }, "isFinal": false },
        "default_management": { "id": { "value": "default_management" }, "isFinal": false },
        "stress_test": { "id": { "value": "stress_test" }, "isFinal": false },
        "liquidity_crisis": { "id": { "value": "liquidity_crisis" }, "isFinal": false }
      },
      "initialState": { "value": "operating" },
      "transitions": [
        {
          "from": { "value": "operating" },
          "to": { "value": "processing_loan" },
          "eventName": "apply_loan",
          "guard": {
            "and": [
              { ">=": [{ "var": "event.creditScore" }, { "var": "state.minCreditScore" }] },
              { ">=": [{ "var": "state.availableCapital" }, { "var": "event.loanAmount" }] }
            ]
          },
          "effect": [
            ["status", "processing_loan"],
            ["currentApplication", {
              "applicantId": { "var": "event.applicantId" },
              "amount": { "var": "event.loanAmount" },
              "creditScore": { "var": "event.creditScore" },
              "dti": { "var": "event.dti" }
            }],
            ["applicationReceivedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "processing_loan" },
          "to": { "value": "loan_servicing" },
          "eventName": "underwrite",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.currentApplication.creditScore" }, 620] },
              { "<=": [{ "var": "state.currentApplication.dti" }, 0.43] },
              { "===": [{ "var": "machines.$federalReserveCid.state.status" }, "stable"] }
            ]
          },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "state.currentApplication.applicantId" },
                "eventName": "loan_funded",
                "payload": {
                  "loanId": { "var": "machineId" },
                  "amount": { "var": "state.currentApplication.amount" },
                  "interestRate": { "+": [
                    { "var": "machines.$federalReserveCid.state.baseRate" },
                    { "var": "state.riskPremium" }
                  ] },
                  "fundedAt": { "var": "event.timestamp" }
                }
              }
            ],
            "status": "loan_servicing",
            "activeLoans": { "+": [{ "var": "state.activeLoans" }, 1] },
            "availableCapital": { "-": [{ "var": "state.availableCapital" }, { "var": "state.currentApplication.amount" }] },
            "loanPortfolio": { "+": [{ "var": "state.loanPortfolio" }, { "var": "state.currentApplication.amount" }] }
          },
          "dependencies": ["$federalReserveCid"]
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "loan_servicing" },
          "eventName": "payment_missed",
          "guard": { "<": [{ "var": "event.missedPayments" }, 3] },
          "effect": [
            ["missedPaymentCount", { "+": [{ "var": "state.missedPaymentCount" }, 1] }],
            ["lastMissedPaymentAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "default_management" },
          "eventName": "payment_missed",
          "guard": { ">=": [{ "var": "event.missedPayments" }, 3] },
          "effect": {
            "_emit": [
              {
                "name": "credit_report",
                "data": {
                  "borrowerId": { "var": "event.borrowerId" },
                  "delinquencyStatus": "90-days",
                  "loanId": { "var": "event.loanId" }
                }
              }
            ],
            "status": "default_management",
            "defaultedLoans": { "+": [{ "var": "state.defaultedLoans" }, 1] },
            "defaultRate": { "/": [{ "var": "state.defaultedLoans" }, { "var": "state.activeLoans" }] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "operating" },
          "to": { "value": "stress_test" },
          "eventName": "fed_directive",
          "guard": { "===": [{ "var": "machines.$federalReserveCid.state.status" }, "stress_testing"] },
          "effect": [
            ["status", "stress_test"],
            ["testInitiatedAt", { "var": "event.timestamp" }],
            ["capitalRatio", { "if": [
              { ">": [{ "var": "state.loanPortfolio" }, 0] },
              { "/": [{ "var": "state.reserveCapital" }, { "var": "state.loanPortfolio" }] },
              1.0
            ] }]
          ],
          "dependencies": ["$federalReserveCid"]
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "stress_test" },
          "eventName": "fed_directive",
          "guard": { "===": [{ "var": "machines.$federalReserveCid.state.status" }, "stress_testing"] },
          "effect": [
            ["status", "stress_test"],
            ["testInitiatedAt", { "var": "event.timestamp" }],
            ["capitalRatio", { "if": [
              { ">": [{ "var": "state.loanPortfolio" }, 0] },
              { "/": [{ "var": "state.reserveCapital" }, { "var": "state.loanPortfolio" }] },
              1.0
            ] }]
          ],
          "dependencies": ["$federalReserveCid"]
        },
        {
          "from": { "value": "stress_test" },
          "to": { "value": "loan_servicing" },
          "eventName": "complete_stress_test",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.capitalRatio" }, 0.08] },
              { "<=": [{ "var": "state.defaultRate" }, 0.10] },
              { ">": [{ "var": "state.activeLoans" }, 0] }
            ]
          },
          "effect": [
            ["status", "loan_servicing"],
            ["testCompletedAt", { "var": "event.timestamp" }],
            ["stressTestPassed", true]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "stress_test" },
          "to": { "value": "operating" },
          "eventName": "complete_stress_test",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.capitalRatio" }, 0.08] },
              { "<=": [{ "var": "state.defaultRate" }, 0.10] }
            ]
          },
          "effect": [
            ["status", "operating"],
            ["testCompletedAt", { "var": "event.timestamp" }],
            ["stressTestPassed", true]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "stress_test" },
          "to": { "value": "liquidity_crisis" },
          "eventName": "complete_stress_test",
          "guard": {
            "or": [
              { "<": [{ "var": "state.capitalRatio" }, 0.08] },
              { ">": [{ "var": "state.defaultRate" }, 0.10] }
            ]
          },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": "$federalReserveCid",
                "eventName": "emergency_lending_request",
                "payload": {
                  "bankId": { "var": "machineId" },
                  "capitalShortfall": { "-": [
                    { "*": [{ "var": "state.loanPortfolio" }, 0.08] },
                    { "var": "state.reserveCapital" }
                  ] },
                  "requestedAt": { "var": "event.timestamp" }
                }
              }
            ],
            "status": "liquidity_crisis",
            "crisisStartedAt": { "var": "event.timestamp" }
          },
          "dependencies": ["$federalReserveCid"]
        },
        {
          "from": { "value": "operating" },
          "to": { "value": "operating" },
          "eventName": "rate_adjustment",
          "guard": true,
          "effect": [
            ["baseRate", { "var": "event.newBaseRate" }],
            ["rateAdjustedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "loan_servicing" },
          "eventName": "rate_adjustment",
          "guard": true,
          "effect": [
            ["baseRate", { "var": "event.newBaseRate" }],
            ["rateAdjustedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "loan_servicing" },
          "eventName": "payment_received",
          "guard": true,
          "effect": [
            ["lastPaymentReceivedAt", { "var": "event.timestamp" }],
            ["totalPaymentsReceived", { "+": [{ "var": "state.totalPaymentsReceived" }, { "var": "event.amount" }] }]
          ],
          "dependencies": []
        }
      ]
    }"""

  def consumerStateMachineJson(): String =
    """{
      "states": {
        "active": { "id": { "value": "active" }, "isFinal": false },
        "shopping": { "id": { "value": "shopping" }, "isFinal": false },
        "service_providing": { "id": { "value": "service_providing" }, "isFinal": false },
        "marketplace_selling": { "id": { "value": "marketplace_selling" }, "isFinal": false },
        "loan_active": { "id": { "value": "loan_active" }, "isFinal": false },
        "debt_current": { "id": { "value": "debt_current" }, "isFinal": false },
        "debt_delinquent": { "id": { "value": "debt_delinquent" }, "isFinal": false }
      },
      "initialState": { "value": "active" },
      "transitions": [
        {
          "from": { "value": "active" },
          "to": { "value": "active" },
          "eventName": "browse_products",
          "guard": { ">=": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.retailerId" },
                "eventName": "process_sale",
                "payload": {
                  "customerId": { "var": "machineId" },
                  "quantity": { "var": "event.quantity" }
                }
              }
            ],
            "balance": { "-": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
            "purchaseCount": { "+": [{ "var": "state.purchaseCount" }, 1] },
            "lastPurchaseAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "active" },
          "to": { "value": "active" },
          "eventName": "provide_service",
          "guard": { "===": [{ "var": "state.serviceType" }, { "var": "event.requestedService" }] },
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.payment" }] }],
            ["serviceRevenue", { "+": [{ "var": "state.serviceRevenue" }, { "var": "event.payment" }] }],
            ["servicesProvided", { "+": [{ "var": "state.servicesProvided" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "active" },
          "to": { "value": "debt_current" },
          "eventName": "loan_funded",
          "guard": true,
          "effect": [
            ["status", "debt_current"],
            ["loanBalance", { "var": "event.amount" }],
            ["loanInterestRate", { "var": "event.interestRate" }],
            ["lenderId", { "var": "event.loanId" }],
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.amount" }] }],
            ["nextPaymentDue", { "+": [{ "var": "event.fundedAt" }, 2592000] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "make_payment",
          "guard": { ">=": [{ "var": "state.balance" }, { "var": "state.monthlyPayment" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "state.lenderId" },
                "eventName": "payment_received",
                "payload": {
                  "borrowerId": { "var": "machineId" },
                  "amount": { "var": "state.monthlyPayment" }
                }
              }
            ],
            "balance": { "-": [{ "var": "state.balance" }, { "var": "state.monthlyPayment" }] },
            "loanBalance": { "-": [
              { "var": "state.loanBalance" },
              { "-": [
                { "var": "state.monthlyPayment" },
                { "*": [{ "var": "state.loanBalance" }, { "var": "state.loanInterestRate" }] }
              ] }
            ] },
            "nextPaymentDue": { "+": [{ "var": "state.nextPaymentDue" }, 2592000] },
            "paymentsRemaining": { "-": [{ "var": "state.paymentsRemaining" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "browse_products",
          "guard": { ">=": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.retailerId" },
                "eventName": "process_sale",
                "payload": {
                  "customerId": { "var": "machineId" },
                  "quantity": { "var": "event.quantity" }
                }
              }
            ],
            "balance": { "-": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
            "purchaseCount": { "+": [{ "var": "state.purchaseCount" }, 1] },
            "lastPurchaseAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "provide_service",
          "guard": { "===": [{ "var": "state.serviceType" }, { "var": "event.requestedService" }] },
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.payment" }] }],
            ["serviceRevenue", { "+": [{ "var": "state.serviceRevenue" }, { "var": "event.payment" }] }],
            ["servicesProvided", { "+": [{ "var": "state.servicesProvided" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_delinquent" },
          "eventName": "check_payment",
          "guard": { ">": [{ "var": "event.timestamp" }, { "var": "state.nextPaymentDue" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "state.lenderId" },
                "eventName": "payment_missed",
                "payload": {
                  "borrowerId": { "var": "machineId" },
                  "missedPayments": { "+": [{ "var": "state.missedPayments" }, 1] }
                }
              }
            ],
            "status": "debt_delinquent",
            "missedPayments": { "+": [{ "var": "state.missedPayments" }, 1] },
            "delinquentSince": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "active" },
          "to": { "value": "marketplace_selling" },
          "eventName": "list_item",
          "guard": true,
          "effect": {
            "_spawn": [
              {
                "childId": { "var": "event.auctionId" },
                "definition": {
                  "states": {
                    "listed": { "id": { "value": "listed" }, "isFinal": false },
                    "bid_received": { "id": { "value": "bid_received" }, "isFinal": false },
                    "sold": { "id": { "value": "sold" }, "isFinal": true },
                    "expired": { "id": { "value": "expired" }, "isFinal": true }
                  },
                  "initialState": { "value": "listed" },
                  "transitions": [
                    {
                      "from": { "value": "listed" },
                      "to": { "value": "bid_received" },
                      "eventName": "place_bid",
                      "guard": { ">=": [{ "var": "event.bidAmount" }, { "var": "state.reservePrice" }] },
                      "effect": [
                        ["status", "bid_received"],
                        ["highestBid", { "var": "event.bidAmount" }],
                        ["highestBidder", { "var": "event.bidderId" }],
                        ["bidReceivedAt", { "var": "event.timestamp" }]
                      ],
                      "dependencies": []
                    },
                    {
                      "from": { "value": "bid_received" },
                      "to": { "value": "sold" },
                      "eventName": "accept_bid",
                      "guard": true,
                      "effect": {
                        "_triggers": [
                          {
                            "targetMachineId": { "var": "parent.machineId" },
                            "eventName": "sale_completed",
                            "payload": {
                              "amount": { "var": "state.highestBid" },
                              "buyer": { "var": "state.highestBidder" },
                              "itemName": { "var": "state.itemName" }
                            }
                          }
                        ],
                        "status": "sold",
                        "soldAt": { "var": "event.timestamp" }
                      },
                      "dependencies": []
                    }
                  ]
                },
                "initialData": {
                  "itemName": { "var": "event.itemName" },
                  "reservePrice": { "var": "event.reservePrice" },
                  "sellerId": { "var": "machineId" },
                  "status": "listed"
                }
              }
            ],
            "status": "marketplace_selling",
            "activeListings": { "+": [{ "var": "state.activeListings" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "marketplace_selling" },
          "to": { "value": "active" },
          "eventName": "sale_completed",
          "guard": true,
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.amount" }] }],
            ["activeListings", { "-": [{ "var": "state.activeListings" }, 1] }],
            ["marketplaceSales", { "+": [{ "var": "state.marketplaceSales" }, 1] }],
            ["status", "active"]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "debt_delinquent" },
          "to": { "value": "debt_delinquent" },
          "eventName": "list_item",
          "guard": true,
          "effect": {
            "_spawn": [
              {
                "childId": { "var": "event.auctionId" },
                "definition": {
                  "states": {
                    "listed": { "id": { "value": "listed" }, "isFinal": false },
                    "bid_received": { "id": { "value": "bid_received" }, "isFinal": false },
                    "sold": { "id": { "value": "sold" }, "isFinal": true },
                    "expired": { "id": { "value": "expired" }, "isFinal": true }
                  },
                  "initialState": { "value": "listed" },
                  "transitions": [
                    {
                      "from": { "value": "listed" },
                      "to": { "value": "bid_received" },
                      "eventName": "place_bid",
                      "guard": { ">=": [{ "var": "event.bidAmount" }, { "var": "state.reservePrice" }] },
                      "effect": [
                        ["status", "bid_received"],
                        ["highestBid", { "var": "event.bidAmount" }],
                        ["highestBidder", { "var": "event.bidderId" }],
                        ["bidReceivedAt", { "var": "event.timestamp" }]
                      ],
                      "dependencies": []
                    },
                    {
                      "from": { "value": "bid_received" },
                      "to": { "value": "sold" },
                      "eventName": "accept_bid",
                      "guard": true,
                      "effect": {
                        "_triggers": [
                          {
                            "targetMachineId": { "var": "parent.machineId" },
                            "eventName": "sale_completed",
                            "payload": {
                              "amount": { "var": "state.highestBid" },
                              "buyer": { "var": "state.highestBidder" },
                              "itemName": { "var": "state.itemName" }
                            }
                          }
                        ],
                        "status": "sold",
                        "soldAt": { "var": "event.timestamp" }
                      },
                      "dependencies": []
                    }
                  ]
                },
                "initialData": {
                  "itemName": { "var": "event.itemName" },
                  "reservePrice": { "var": "event.reservePrice" },
                  "sellerId": { "var": "machineId" },
                  "status": "listed"
                }
              }
            ],
            "activeListings": { "+": [{ "var": "state.activeListings" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_delinquent" },
          "to": { "value": "debt_delinquent" },
          "eventName": "sale_completed",
          "guard": true,
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.amount" }] }],
            ["activeListings", { "-": [{ "var": "state.activeListings" }, 1] }],
            ["marketplaceSales", { "+": [{ "var": "state.marketplaceSales" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "active" },
          "to": { "value": "active" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_delinquent" },
          "to": { "value": "debt_delinquent" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        }
      ]
    }"""

  def federalReserveStateMachineJson(bankCids: Set[UUID]): String = {
    val bankTriggers = bankCids
      .map { bankCid =>
        s"""{
        "targetMachineId": "$bankCid",
        "eventName": "rate_adjustment",
        "payload": {
          "newBaseRate": { "+": [{ "var": "state.baseRate" }, 0.0025] },
          "direction": "increase"
        }
      }"""
      }
      .mkString("[", ",", "]")

    val stressTestTriggers = bankCids
      .map { bankCid =>
        s"""{
        "targetMachineId": "$bankCid",
        "eventName": "fed_directive",
        "payload": { "directive": "stress_test" }
      }"""
      }
      .mkString("[", ",", "]")

    s"""{
      "states": {
        "stable": { "id": { "value": "stable" }, "isFinal": false },
        "rate_review": { "id": { "value": "rate_review" }, "isFinal": false },
        "rate_increased": { "id": { "value": "rate_increased" }, "isFinal": false },
        "rate_decreased": { "id": { "value": "rate_decreased" }, "isFinal": false },
        "stress_testing": { "id": { "value": "stress_testing" }, "isFinal": false },
        "emergency_lending": { "id": { "value": "emergency_lending" }, "isFinal": false }
      },
      "initialState": { "value": "stable" },
      "transitions": [
        {
          "from": { "value": "stable" },
          "to": { "value": "rate_review" },
          "eventName": "quarterly_meeting",
          "guard": true,
          "effect": [
            ["status", "rate_review"],
            ["reviewStartedAt", { "var": "event.timestamp" }],
            ["inflationRate", { "var": "event.inflationRate" }],
            ["unemploymentRate", { "var": "event.unemploymentRate" }],
            ["gdpGrowth", { "var": "event.gdpGrowth" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "rate_review" },
          "to": { "value": "stable" },
          "eventName": "set_rate",
          "guard": { ">": [{ "var": "state.inflationRate" }, 0.025] },
          "effect": {
            "_triggers": $bankTriggers,
            "status": "stable",
            "baseRate": { "+": [{ "var": "state.baseRate" }, 0.0025] },
            "lastAdjustmentAt": { "var": "event.timestamp" },
            "rateChangeCount": { "+": [{ "var": "state.rateChangeCount" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "stable" },
          "to": { "value": "stress_testing" },
          "eventName": "initiate_stress_test",
          "guard": {
            "or": [
              { ">": [{ "var": "event.marketVolatility" }, 0.30] },
              { ">": [{ "var": "event.systemicRisk" }, 0.50] }
            ]
          },
          "effect": {
            "_triggers": $stressTestTriggers,
            "status": "stress_testing",
            "testInitiatedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "stable" },
          "to": { "value": "emergency_lending" },
          "eventName": "emergency_lending_request",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.bankId" },
                "eventName": "receive_emergency_funds",
                "payload": {
                  "amount": { "var": "event.capitalShortfall" },
                  "interestRate": { "+": [{ "var": "state.baseRate" }, 0.01] },
                  "term": 90
                }
              }
            ],
            "_emit": [
              {
                "name": "regulatory_notice",
                "data": {
                  "type": "emergency_lending",
                  "recipient": { "var": "event.bankId" },
                  "amount": { "var": "event.capitalShortfall" }
                }
              }
            ],
            "status": "emergency_lending",
            "emergencyLoansIssued": { "+": [{ "var": "state.emergencyLoansIssued" }, 1] },
            "totalEmergencyLending": { "+": [{ "var": "state.totalEmergencyLending" }, { "var": "event.capitalShortfall" }] }
          },
          "dependencies": []
        }
      ]
    }"""
  }

  def governanceStateMachineJson(
    manufacturerCids: Set[UUID],
    retailerCids:     Set[UUID],
    consumerCids:     Set[UUID]
  ): String = {
    val allTaxpayerCids = manufacturerCids ++ retailerCids ++ consumerCids
    val taxCollectionTriggers = allTaxpayerCids
      .map { cid =>
        s"""{
        "targetMachineId": "$cid",
        "eventName": "pay_taxes",
        "payload": {
          "taxPeriod": { "var": "event.taxPeriod" },
          "taxRate": { "var": "event.taxRate" },
          "collectionDate": { "var": "event.timestamp" },
          "governanceId": { "var": "event.governanceId" }
        }
      }"""
      }
      .mkString("[", ",\n        ", "]")

    s"""{
      "states": {
        "monitoring": { "id": { "value": "monitoring" }, "isFinal": false },
        "tax_collection": { "id": { "value": "tax_collection" }, "isFinal": false },
        "audit": { "id": { "value": "audit" }, "isFinal": false },
        "compliant": { "id": { "value": "compliant" }, "isFinal": false }
      },
      "initialState": { "value": "monitoring" },
      "transitions": [
        {
          "from": { "value": "monitoring" },
          "to": { "value": "tax_collection" },
          "eventName": "collect_taxes",
          "guard": true,
          "effect": {
            "_triggers": $taxCollectionTriggers,
            "taxPeriod": { "var": "event.taxPeriod" },
            "taxRate": { "var": "event.taxRate" },
            "collectionInitiatedAt": { "var": "event.timestamp" },
            "expectedTaxpayers": ${allTaxpayerCids.size},
            "taxpayersCompliant": 0
          },
          "dependencies": []
        },
        {
          "from": { "value": "tax_collection" },
          "to": { "value": "tax_collection" },
          "eventName": "tax_payment_received",
          "guard": { "<": [{ "+": [{ "var": "state.taxpayersCompliant" }, 1] }, { "var": "state.expectedTaxpayers" }] },
          "effect": [
            ["totalTaxesCollected", { "+": [{ "var": "state.totalTaxesCollected" }, { "var": "event.taxAmount" }] }],
            ["lastPaymentFrom", { "var": "event.taxpayerId" }],
            ["lastPaymentAt", { "var": "event.timestamp" }],
            ["taxpayersCompliant", { "+": [{ "var": "state.taxpayersCompliant" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "tax_collection" },
          "to": { "value": "monitoring" },
          "eventName": "tax_payment_received",
          "guard": { "===": [{ "+": [{ "var": "state.taxpayersCompliant" }, 1] }, { "var": "state.expectedTaxpayers" }] },
          "effect": [
            ["totalTaxesCollected", { "+": [{ "var": "state.totalTaxesCollected" }, { "var": "event.taxAmount" }] }],
            ["lastPaymentFrom", { "var": "event.taxpayerId" }],
            ["lastPaymentAt", { "var": "event.timestamp" }],
            ["taxpayersCompliant", { "+": [{ "var": "state.taxpayersCompliant" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "monitoring" },
          "to": { "value": "monitoring" },
          "eventName": "tax_payment_received",
          "guard": true,
          "effect": [
            ["totalTaxesCollected", { "+": [{ "var": "state.totalTaxesCollected" }, { "var": "event.taxAmount" }] }],
            ["lastPaymentFrom", { "var": "event.taxpayerId" }],
            ["lastPaymentAt", { "var": "event.timestamp" }],
            ["taxpayersCompliant", { "+": [{ "var": "state.taxpayersCompliant" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "monitoring" },
          "to": { "value": "audit" },
          "eventName": "initiate_audit",
          "guard": true,
          "effect": {
            "auditTarget": { "var": "event.targetId" },
            "auditInitiatedAt": { "var": "event.timestamp" },
            "investigationsActive": { "+": [{ "var": "state.investigationsActive" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "audit" },
          "to": { "value": "compliant" },
          "eventName": "audit_complete",
          "guard": { "===": [{ "var": "event.finding" }, "compliant"] },
          "effect": {
            "_emit": [
              {
                "name": "audit_report",
                "data": {
                  "target": { "var": "state.auditTarget" },
                  "finding": "compliant",
                  "completedAt": { "var": "event.timestamp" }
                }
              }
            ],
            "investigationsActive": { "-": [{ "var": "state.investigationsActive" }, 1] },
            "auditCompletedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "compliant" },
          "to": { "value": "monitoring" },
          "eventName": "return_to_monitoring",
          "guard": true,
          "effect": [],
          "dependencies": []
        }
      ]
    }"""
  }

  test("riverdale-economy: complex economic simulation with many participants") {
    securityProviderResource.use { implicit s =>
      for {
        implicit0(l0ctx: L0NodeContext[IO]) <- MockL0NodeContext.make[IO]

        // Create registry with all 26 participants (Alice through Zoe)
        registry <- ParticipantRegistry.create[IO](
          Set(
            Alice,
            Bob,
            Charlie,
            Dave,
            Eve,
            Faythe,
            Grace,
            Heidi,
            Ivan,
            Judy,
            Karl,
            Lance,
            Mallory,
            Niaj,
            Oscar,
            Peggy,
            Quentin,
            Ruth,
            Sybil,
            Trent,
            Ursula,
            Victor,
            Walter,
            Xavier,
            Yolanda,
            Zoe
          )
        )
        combiner <- Combiner.make[IO]().pure[IO]
        ordinal  <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)

        // Generate UUIDs for all 26 participants
        aliceCid   <- UUIDGen.randomUUID[IO]
        bobCid     <- UUIDGen.randomUUID[IO]
        charlieCid <- UUIDGen.randomUUID[IO]
        daveCid    <- UUIDGen.randomUUID[IO]
        eveCid     <- UUIDGen.randomUUID[IO]

        faytheCid <- UUIDGen.randomUUID[IO]
        graceCid  <- UUIDGen.randomUUID[IO]
        heidiCid  <- UUIDGen.randomUUID[IO]
        ivanCid   <- UUIDGen.randomUUID[IO]
        judyCid   <- UUIDGen.randomUUID[IO]
        karlCid   <- UUIDGen.randomUUID[IO]

        lanceCid   <- UUIDGen.randomUUID[IO]
        malloryCid <- UUIDGen.randomUUID[IO]
        niajCid    <- UUIDGen.randomUUID[IO]

        oscarCid   <- UUIDGen.randomUUID[IO]
        peggyCid   <- UUIDGen.randomUUID[IO]
        quentinCid <- UUIDGen.randomUUID[IO]

        ruthCid   <- UUIDGen.randomUUID[IO]
        sybilCid  <- UUIDGen.randomUUID[IO]
        trentCid  <- UUIDGen.randomUUID[IO]
        ursulaCid <- UUIDGen.randomUUID[IO]
        victorCid <- UUIDGen.randomUUID[IO]
        walterCid <- UUIDGen.randomUUID[IO]

        xavierCid  <- UUIDGen.randomUUID[IO]
        yolandaCid <- UUIDGen.randomUUID[IO]
        zoeCid     <- UUIDGen.randomUUID[IO]

        // Create initial state data for each participant type
        manufacturerInitialData = MapValue(
          Map(
            "rawMaterials"      -> IntValue(1000),
            "inventory"         -> IntValue(420),
            "maxInventory"      -> IntValue(500),
            "requiredMaterials" -> IntValue(50),
            "batchSize"         -> IntValue(0),
            "totalProduced"     -> IntValue(0),
            "shipmentCount"     -> IntValue(0),
            "taxesPaid"         -> FloatValue(0.0),
            "status"            -> StrValue("idle")
          )
        )

        retailerInitialData = MapValue(
          Map(
            "stock"            -> IntValue(15),
            "reorderThreshold" -> IntValue(20),
            "reorderQuantity"  -> IntValue(100),
            "revenue"          -> IntValue(0),
            "salesCount"       -> IntValue(0),
            "pricePerUnit"     -> IntValue(10),
            "taxesPaid"        -> FloatValue(0.0),
            "status"           -> StrValue("open")
          )
        )

        platformInitialData = MapValue(
          Map(
            "responseTime" -> IntValue(100),
            "capacity"     -> IntValue(1000),
            "transactions" -> IntValue(0),
            "status"       -> StrValue("active")
          )
        )

        bankInitialData = MapValue(
          Map(
            "baseRate"              -> FloatValue(0.04),
            "availableCapital"      -> IntValue(1000000),
            "activeLoans"           -> IntValue(0),
            "defaultedLoans"        -> IntValue(0),
            "loanPortfolio"         -> IntValue(0),
            "reserveCapital"        -> IntValue(100000),
            "minCreditScore"        -> IntValue(600),
            "riskPremium"           -> FloatValue(0.02),
            "defaultRate"           -> FloatValue(0.0),
            "missedPaymentCount"    -> IntValue(0),
            "totalPaymentsReceived" -> IntValue(0),
            "status"                -> StrValue("operating")
          )
        )

        consumerInitialData = MapValue(
          Map(
            "balance"           -> IntValue(5000),
            "serviceRevenue"    -> IntValue(0),
            "loanBalance"       -> IntValue(0),
            "purchaseCount"     -> IntValue(0),
            "servicesProvided"  -> IntValue(0),
            "activeListings"    -> IntValue(0),
            "marketplaceSales"  -> IntValue(0),
            "serviceType"       -> StrValue("software_development"),
            "monthlyPayment"    -> IntValue(300),
            "missedPayments"    -> IntValue(0),
            "paymentsRemaining" -> IntValue(36),
            "taxesPaid"         -> FloatValue(0.0),
            "status"            -> StrValue("active")
          )
        )

        fedInitialData = MapValue(
          Map(
            "baseRate"              -> FloatValue(0.04),
            "inflationRate"         -> FloatValue(0.02),
            "unemploymentRate"      -> FloatValue(0.04),
            "gdpGrowth"             -> FloatValue(0.03),
            "emergencyLoansIssued"  -> IntValue(0),
            "totalEmergencyLending" -> IntValue(0),
            "rateChangeCount"       -> IntValue(0),
            "status"                -> StrValue("stable")
          )
        )

        governanceInitialData = MapValue(
          Map(
            "investigationsActive" -> IntValue(0),
            "enforcementActions"   -> IntValue(0),
            "totalTaxesCollected"  -> IntValue(0),
            "taxpayersCompliant"   -> IntValue(0),
            "expectedTaxpayers"    -> IntValue(0)
          )
        )

        // Create state machine definitions from JSON
        manufacturerDef <- decodeStateMachine(manufacturerStateMachineJson())
        bankDef         <- decodeStateMachine(bankStateMachineJson(yolandaCid.toString))
        consumerDef     <- decodeStateMachine(consumerStateMachineJson())
        fedDef          <- decodeStateMachine(federalReserveStateMachineJson(Set(oscarCid, peggyCid, quentinCid)))
        governanceDef <- decodeStateMachine(
          governanceStateMachineJson(
            Set(aliceCid, bobCid, charlieCid, daveCid),
            Set(heidiCid, graceCid, ivanCid),
            Set(ruthCid, sybilCid, victorCid)
          )
        )

        // Initialize manufacturer fibers
        aliceHash <- (manufacturerInitialData: JsonLogicValue).computeDigest
        aliceFiber = Records.StateMachineFiberRecord(
          cid = aliceCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = manufacturerDef,
          currentState = StateId("idle"),
          stateData = manufacturerInitialData.copy(value =
            manufacturerInitialData.value + ("businessName" -> StrValue("SteelCore Manufacturing"))
          ),
          stateDataHash = aliceHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        bobHash <- (manufacturerInitialData: JsonLogicValue).computeDigest
        bobFiber = Records.StateMachineFiberRecord(
          cid = bobCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = manufacturerDef,
          currentState = StateId("idle"),
          stateData = manufacturerInitialData.copy(value =
            manufacturerInitialData.value + ("businessName" -> StrValue("AgriGrow Farms"))
          ),
          stateDataHash = bobHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Bob).map(registry.addresses),
          status = FiberStatus.Active
        )

        charlieInitialData = manufacturerInitialData.copy(value =
          manufacturerInitialData.value ++ Map(
            "rawMaterials" -> IntValue(40), // Below required materials threshold
            "businessName" -> StrValue("TechParts Inc")
          )
        )
        charlieHash <- (charlieInitialData: JsonLogicValue).computeDigest
        charlieFiber = Records.StateMachineFiberRecord(
          cid = charlieCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = manufacturerDef,
          currentState = StateId("idle"),
          stateData = charlieInitialData,
          stateDataHash = charlieHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Charlie).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Initialize retailer fibers (with dependencies on manufacturers)
        heidiRetailerDef <- decodeStateMachine(retailerStateMachineJson(aliceCid.toString, niajCid.toString))
        heidiData = retailerInitialData.copy(value =
          retailerInitialData.value + ("businessName" -> StrValue("QuickFix Hardware"))
        )
        heidiHash <- (heidiData: JsonLogicValue).computeDigest
        heidiFiber = Records.StateMachineFiberRecord(
          cid = heidiCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = heidiRetailerDef,
          currentState = StateId("open"),
          stateData = heidiData,
          stateDataHash = heidiHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Heidi).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Initialize platform fibers
        niajData = platformInitialData.copy(value =
          platformInitialData.value + ("businessName" -> StrValue("PayFlow Processing"))
        )
        niajHash <- (niajData: JsonLogicValue).computeDigest
        niajFiber = Records.StateMachineFiberRecord(
          cid = niajCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = manufacturerDef, // Simplified for test
          currentState = StateId("idle"),
          stateData = niajData,
          stateDataHash = niajHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Niaj).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Initialize bank fibers
        oscarHash <- (bankInitialData: JsonLogicValue).computeDigest
        oscarFiber = Records.StateMachineFiberRecord(
          cid = oscarCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = bankDef,
          currentState = StateId("operating"),
          stateData = bankInitialData.copy(value =
            bankInitialData.value + ("businessName" -> StrValue("Riverdale National Bank"))
          ),
          stateDataHash = oscarHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Oscar).map(registry.addresses),
          status = FiberStatus.Active
        )

        peggyHash <- (bankInitialData: JsonLogicValue).computeDigest
        peggyFiber = Records.StateMachineFiberRecord(
          cid = peggyCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = bankDef,
          currentState = StateId("operating"),
          stateData =
            bankInitialData.copy(value = bankInitialData.value + ("businessName" -> StrValue("SecureCredit Union"))),
          stateDataHash = peggyHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Peggy).map(registry.addresses),
          status = FiberStatus.Active
        )

        quentinHash <- (bankInitialData: JsonLogicValue).computeDigest
        quentinFiber = Records.StateMachineFiberRecord(
          cid = quentinCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = bankDef,
          currentState = StateId("operating"),
          stateData = bankInitialData.copy(value =
            bankInitialData.value + ("businessName" -> StrValue("VentureForward Capital"))
          ),
          stateDataHash = quentinHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Quentin).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Initialize consumer fibers
        ruthHash <- (consumerInitialData: JsonLogicValue).computeDigest
        ruthFiber = Records.StateMachineFiberRecord(
          cid = ruthCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = consumerDef,
          currentState = StateId("active"),
          stateData = consumerInitialData.copy(value =
            consumerInitialData.value + ("name" -> StrValue("Ruth - Software Developer"))
          ),
          stateDataHash = ruthHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Ruth).map(registry.addresses),
          status = FiberStatus.Active
        )

        sybilInitialData = consumerInitialData.copy(value =
          consumerInitialData.value ++ Map(
            "balance" -> IntValue(3000), // Lower balance than Ruth
            "name"    -> StrValue("Sybil - Crafts Seller")
          )
        )
        sybilHash <- (sybilInitialData: JsonLogicValue).computeDigest
        sybilFiber = Records.StateMachineFiberRecord(
          cid = sybilCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = consumerDef,
          currentState = StateId("active"),
          stateData = sybilInitialData,
          stateDataHash = sybilHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Sybil).map(registry.addresses),
          status = FiberStatus.Active
        )

        victorInitialData = consumerInitialData.copy(value =
          consumerInitialData.value ++ Map(
            "balance" -> IntValue(10000), // High balance for bidding
            "name"    -> StrValue("Victor - Collector")
          )
        )
        victorHash <- (victorInitialData: JsonLogicValue).computeDigest
        victorFiber = Records.StateMachineFiberRecord(
          cid = victorCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = consumerDef,
          currentState = StateId("active"),
          stateData = victorInitialData,
          stateDataHash = victorHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Victor).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Initialize Federal Reserve fiber
        yolandaHash <- (fedInitialData: JsonLogicValue).computeDigest
        yolandaFiber = Records.StateMachineFiberRecord(
          cid = yolandaCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = fedDef,
          currentState = StateId("stable"),
          stateData =
            fedInitialData.copy(value = fedInitialData.value + ("name" -> StrValue("Federal Reserve Branch"))),
          stateDataHash = yolandaHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Yolanda).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Initialize Governance fiber
        xavierHash <- (governanceInitialData: JsonLogicValue).computeDigest
        xavierFiber = Records.StateMachineFiberRecord(
          cid = xavierCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = governanceDef,
          currentState = StateId("monitoring"),
          stateData = governanceInitialData.copy(value =
            governanceInitialData.value + ("name" -> StrValue("Riverdale Governance"))
          ),
          stateDataHash = xavierHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Xavier).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Create initial state with key participants
        initialState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            aliceCid   -> aliceFiber,
            bobCid     -> bobFiber,
            charlieCid -> charlieFiber,
            heidiCid   -> heidiFiber,
            niajCid    -> niajFiber,
            oscarCid   -> oscarFiber,
            peggyCid   -> peggyFiber,
            quentinCid -> quentinFiber,
            ruthCid    -> ruthFiber,
            sybilCid   -> sybilFiber,
            victorCid  -> victorFiber,
            xavierCid  -> xavierFiber,
            yolandaCid -> yolandaFiber
          )
        )

        // ========================================
        // PHASE 1: NORMAL ECONOMIC ACTIVITY
        // ========================================

        // Event 1: Federal Reserve quarterly meeting (high inflation scenario)
        update1 = Updates.TransitionStateMachine(
          yolandaCid,
          "quarterly_meeting",
          MapValue(
            Map(
              "timestamp"        -> IntValue(1000),
              "inflationRate"    -> FloatValue(0.03), // Above 0.025 threshold triggers rate increase
              "unemploymentRate" -> FloatValue(0.04),
              "gdpGrowth"        -> FloatValue(0.03)
            )
          )
        )
        proof1 <- registry.generateProofs(update1, Set(Yolanda))
        state1 <- combiner.insert(initialState, Signed(update1, proof1))

        // Event 2: Fed sets rate (returns to stable, broadcasts rate_adjustment to all banks)
        update2 = Updates.TransitionStateMachine(
          yolandaCid,
          "set_rate",
          MapValue(Map("timestamp" -> IntValue(1050)))
        )
        proof2 <- registry.generateProofs(update2, Set(Yolanda))
        state2 <- combiner.insert(state1, Signed(update2, proof2))

        // Event 3: Alice schedules production
        update3 = Updates.TransitionStateMachine(
          aliceCid,
          "schedule_production",
          MapValue(
            Map(
              "timestamp" -> IntValue(1100),
              "batchSize" -> IntValue(100)
            )
          )
        )
        proof3 <- registry.generateProofs(update3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(update3, proof3))

        // Event 4: Alice starts production
        update4 = Updates.TransitionStateMachine(
          aliceCid,
          "start_production",
          MapValue(Map("timestamp" -> IntValue(1200)))
        )
        proof4 <- registry.generateProofs(update4, Set(Alice))
        state4 <- combiner.insert(state3, Signed(update4, proof4))

        // Event 5: Alice completes batch
        update5 = Updates.TransitionStateMachine(
          aliceCid,
          "complete_batch",
          MapValue(Map("timestamp" -> IntValue(2000)))
        )
        proof5 <- registry.generateProofs(update5, Set(Alice))
        state5 <- combiner.insert(state4, Signed(update5, proof5))

        // Event 6: Heidi checks inventory (should trigger order to Alice)
        update6 = Updates.TransitionStateMachine(
          heidiCid,
          "check_inventory",
          MapValue(
            Map(
              "timestamp"  -> IntValue(2100),
              "shipmentId" -> StrValue("SHIP-001")
            )
          )
        )
        proof6 <- registry.generateProofs(update6, Set(Heidi))
        state6 <- combiner.insert(state5, Signed(update6, proof6))

        // Verify Heidi's state changed to inventory_low
        heidiFiberAfterCheck = state6.calculated.stateMachines
          .get(heidiCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Event 7: Ruth applies for loan from Oscar
        update7 = Updates.TransitionStateMachine(
          oscarCid,
          "apply_loan",
          MapValue(
            Map(
              "timestamp"   -> IntValue(2200),
              "applicantId" -> StrValue(ruthCid.toString),
              "loanAmount"  -> IntValue(10000),
              "creditScore" -> IntValue(720),
              "dti"         -> FloatValue(0.35)
            )
          )
        )
        proof7 <- registry.generateProofs(update7, Set(Oscar))
        state7 <- combiner.insert(state6, Signed(update7, proof7))

        // Event 8: Oscar underwrites loan (Fed must be in stable state)
        update8 = Updates.TransitionStateMachine(
          oscarCid,
          "underwrite",
          MapValue(Map("timestamp" -> IntValue(2300)))
        )
        proof8 <- registry.generateProofs(update8, Set(Oscar))
        state8 <- combiner.insert(state7, Signed(update8, proof8))

        // ========================================
        // PHASE 2: SUPPLY CHAIN DISRUPTION
        // ========================================

        // Event 9: Supply shortage at Charlie's TechParts
        update9 = Updates.TransitionStateMachine(
          charlieCid,
          "check_materials",
          MapValue(Map("timestamp" -> IntValue(3000)))
        )
        proof9 <- registry.generateProofs(update9, Set(Charlie))
        state9 <- combiner.insert(state8, Signed(update9, proof9))

        // Verify Charlie entered supply_shortage state
        charlieFiberAfterShortage = state9.calculated.stateMachines
          .get(charlieCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // ========================================
        // PHASE 3: BOB PRODUCTION CYCLE
        // ========================================

        // Event 10: Bob schedules production
        update10 = Updates.TransitionStateMachine(
          bobCid,
          "schedule_production",
          MapValue(
            Map(
              "timestamp" -> IntValue(3100),
              "batchSize" -> IntValue(80)
            )
          )
        )
        proof10 <- registry.generateProofs(update10, Set(Bob))
        state10 <- combiner.insert(state9, Signed(update10, proof10))

        // Event 11: Bob starts production
        update11 = Updates.TransitionStateMachine(
          bobCid,
          "start_production",
          MapValue(Map("timestamp" -> IntValue(3200)))
        )
        proof11 <- registry.generateProofs(update11, Set(Bob))
        state11 <- combiner.insert(state10, Signed(update11, proof11))

        // Event 12: Bob completes batch
        update12 = Updates.TransitionStateMachine(
          bobCid,
          "complete_batch",
          MapValue(Map("timestamp" -> IntValue(4000)))
        )
        proof12 <- registry.generateProofs(update12, Set(Bob))
        state12 <- combiner.insert(state11, Signed(update12, proof12))

        // ========================================
        // PHASE 4: GRACE RETAILER CROSS-CHAIN
        // ========================================

        // Initialize Grace (retailer depending on Bob's farm)
        graceRetailerDef <- decodeStateMachine(retailerStateMachineJson(bobCid.toString, niajCid.toString))
        graceData = retailerInitialData.copy(value =
          retailerInitialData.value + ("businessName" -> StrValue("FreshFoods Market"))
        )
        graceHash <- (graceData: JsonLogicValue).computeDigest
        graceFiber = Records.StateMachineFiberRecord(
          cid = graceCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = graceRetailerDef,
          currentState = StateId("open"),
          stateData = graceData,
          stateDataHash = graceHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Grace).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Add Grace to the state
        state13 <- state12.withRecord[IO](graceCid, graceFiber)

        // Event 13: Grace checks inventory (triggers order to Bob)
        update13 = Updates.TransitionStateMachine(
          graceCid,
          "check_inventory",
          MapValue(
            Map(
              "timestamp"  -> IntValue(4100),
              "shipmentId" -> StrValue("SHIP-002")
            )
          )
        )
        proof13 <- registry.generateProofs(update13, Set(Grace))
        state14 <- combiner.insert(state13, Signed(update13, proof13))

        // ========================================
        // PHASE 5: RUTH CONSUMER SHOPPING
        // ========================================

        // Event 14: Heidi reopens after restocking
        update14 = Updates.TransitionStateMachine(
          heidiCid,
          "reopen",
          MapValue(Map("timestamp" -> IntValue(4150)))
        )
        proof14 <- registry.generateProofs(update14, Set(Heidi))
        state15 <- combiner.insert(state14, Signed(update14, proof14))

        // Event 15: Ruth browses products at Heidi's store
        update15 = Updates.TransitionStateMachine(
          ruthCid,
          "browse_products",
          MapValue(
            Map(
              "timestamp"    -> IntValue(4200),
              "retailerId"   -> StrValue(heidiCid.toString),
              "expectedCost" -> IntValue(50),
              "quantity"     -> IntValue(5)
            )
          )
        )
        proof15 <- registry.generateProofs(update15, Set(Ruth))
        state16 <- combiner.insert(state15, Signed(update15, proof15))

        // ========================================
        // PHASE 6: SYBIL CONSUMER DELINQUENCY
        // ========================================

        // Event 16: Sybil applies for loan from Peggy
        update16 = Updates.TransitionStateMachine(
          peggyCid,
          "apply_loan",
          MapValue(
            Map(
              "timestamp"   -> IntValue(4300),
              "applicantId" -> StrValue(sybilCid.toString),
              "loanAmount"  -> IntValue(5000),
              "creditScore" -> IntValue(640),
              "dti"         -> FloatValue(0.42)
            )
          )
        )
        proof16 <- registry.generateProofs(update16, Set(Peggy))
        state17 <- combiner.insert(state16, Signed(update16, proof16))

        // Event 17: Peggy underwrites loan for Sybil
        update17 = Updates.TransitionStateMachine(
          peggyCid,
          "underwrite",
          MapValue(Map("timestamp" -> IntValue(4400)))
        )
        proof17 <- registry.generateProofs(update17, Set(Peggy))
        state18 <- combiner.insert(state17, Signed(update17, proof17))
        // Event 18: Sybil checks payment (misses payment - timestamp way past nextPaymentDue)
        update18 = Updates.TransitionStateMachine(
          sybilCid,
          "check_payment",
          MapValue(Map("timestamp" -> IntValue(7100000))) // Way past due date (2592000 + 4400 = 2596400)
        )
        proof18 <- registry.generateProofs(update18, Set(Sybil))
        state19 <- combiner.insert(state18, Signed(update18, proof18))

        // ========================================
        // PHASE 7: STRESS TEST SCENARIO
        // ========================================

        // Event 19: Fed initiates stress test due to high market volatility
        update19 = Updates.TransitionStateMachine(
          yolandaCid,
          "initiate_stress_test",
          MapValue(
            Map(
              "timestamp"        -> IntValue(7200000),
              "marketVolatility" -> FloatValue(0.35), // Above 0.30 threshold
              "systemicRisk"     -> FloatValue(0.45)
            )
          )
        )
        proof19 <- registry.generateProofs(update19, Set(Yolanda))
        state20 <- combiner.insert(state19, Signed(update19, proof19))

        // Event 20: Oscar completes stress test (passes - good capital ratio)
        update20 = Updates.TransitionStateMachine(
          oscarCid,
          "complete_stress_test",
          MapValue(Map("timestamp" -> IntValue(7300000)))
        )
        proof20 <- registry.generateProofs(update20, Set(Oscar))
        state21 <- combiner.insert(state20, Signed(update20, proof20))

        // Event 21: Peggy completes stress test (passes - good capital ratio)
        update21 = Updates.TransitionStateMachine(
          peggyCid,
          "complete_stress_test",
          MapValue(Map("timestamp" -> IntValue(7300050)))
        )
        proof21 <- registry.generateProofs(update21, Set(Peggy))
        state22 <- combiner.insert(state21, Signed(update21, proof21))

        // Event 22: Quentin completes stress test (passes - good capital ratio)
        update22 = Updates.TransitionStateMachine(
          quentinCid,
          "complete_stress_test",
          MapValue(Map("timestamp" -> IntValue(7300100)))
        )
        proof22 <- registry.generateProofs(update22, Set(Quentin))
        state23 <- combiner.insert(state22, Signed(update22, proof22))

        // ========================================
        // PHASE 8: HEIDI HOLIDAY SALE
        // ========================================

        // Event 23: Heidi starts holiday sale (20% discount)
        update23 = Updates.TransitionStateMachine(
          heidiCid,
          "start_sale",
          MapValue(
            Map(
              "timestamp"    -> IntValue(7400000),
              "season"       -> StrValue("holiday"),
              "discountRate" -> FloatValue(0.20)
            )
          )
        )
        proof23 <- registry.generateProofs(update23, Set(Heidi))
        state24 <- combiner.insert(state23, Signed(update23, proof23))

        // Capture stress test state for validation
        oscarFiberAfterStressTest = state23.calculated.stateMachines
          .get(oscarCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        peggyFiberAfterStressTest = state23.calculated.stateMachines
          .get(peggyCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        quentinFiberAfterStressTest = state23.calculated.stateMachines
          .get(quentinCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        yolandaFiberAfterStressTest = state23.calculated.stateMachines
          .get(yolandaCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Capture holiday sale state for validation
        heidiFiberAfterSale = state24.calculated.stateMachines
          .get(heidiCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // ========================================
        // PHASE 9: C2C MARKETPLACE (AUCTION)
        // ========================================

        auctionCid <- UUIDGen.randomUUID[IO]

        // Event 24: Sybil lists handmade craft item for auction (spawns auction child machine)
        update24 = Updates.TransitionStateMachine(
          sybilCid,
          "list_item",
          MapValue(
            Map(
              "auctionId"    -> StrValue(auctionCid.toString),
              "itemName"     -> StrValue("Handmade Ceramic Vase"),
              "reservePrice" -> IntValue(500),
              "timestamp"    -> IntValue(7500000)
            )
          )
        )
        proof24 <- registry.generateProofs(update24, Set(Sybil))
        state25 <- combiner.insert(state24, Signed(update24, proof24))

        // Event 25: Victor places bid on Sybil's auction (bid exceeds reserve price)
        update25 = Updates.TransitionStateMachine(
          auctionCid,
          "place_bid",
          MapValue(
            Map(
              "bidAmount" -> IntValue(750),
              "bidderId"  -> StrValue(victorCid.toString),
              "timestamp" -> IntValue(7600000)
            )
          )
        )
        proof25 <- registry.generateProofs(update25, Set(Victor))
        state26 <- combiner.insert(state25, Signed(update25, proof25))

        // Event 26: Auction accepts bid (triggers sale_completed to Sybil)
        update26 = Updates.TransitionStateMachine(
          auctionCid,
          "accept_bid",
          MapValue(Map("timestamp" -> IntValue(7700000)))
        )
        proof26 <- registry.generateProofs(update26, Set(Sybil))
        state27 <- combiner.insert(state26, Signed(update26, proof26))

        // Capture C2C marketplace state for validation
        auctionFiberAfterBidAccepted = state27.calculated.stateMachines
          .get(auctionCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        sybilFiberAfterSale = state27.calculated.stateMachines
          .get(sybilCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // ========================================
        // PHASE 10: RUTH MAKES LOAN PAYMENT
        // ========================================

        // Event 27: Ruth makes first loan payment to Oscar
        update27 = Updates.TransitionStateMachine(
          ruthCid,
          "make_payment",
          MapValue(Map("timestamp" -> IntValue(7750000)))
        )
        proof27 <- registry.generateProofs(update27, Set(Ruth))
        state28 <- combiner.insert(state27, Signed(update27, proof27))

        // ========================================
        // PHASE 11: SECOND AUCTION (Victor sells)
        // ========================================

        auction2Cid <- UUIDGen.randomUUID[IO]

        // Event 28: Victor lists antique item for auction
        update28 = Updates.TransitionStateMachine(
          victorCid,
          "list_item",
          MapValue(
            Map(
              "auctionId"    -> StrValue(auction2Cid.toString),
              "itemName"     -> StrValue("Vintage Watch Collection"),
              "reservePrice" -> IntValue(2000),
              "timestamp"    -> IntValue(7800000)
            )
          )
        )
        proof28 <- registry.generateProofs(update28, Set(Victor))
        state29 <- combiner.insert(state28, Signed(update28, proof28))

        // Event 29: Ruth places bid on Victor's auction (using loan proceeds)
        update29 = Updates.TransitionStateMachine(
          auction2Cid,
          "place_bid",
          MapValue(
            Map(
              "bidAmount" -> IntValue(2500),
              "bidderId"  -> StrValue(ruthCid.toString),
              "timestamp" -> IntValue(7900000)
            )
          )
        )
        proof29 <- registry.generateProofs(update29, Set(Ruth))
        state30 <- combiner.insert(state29, Signed(update29, proof29))

        // Event 30: Victor accepts Ruth's bid
        update30 = Updates.TransitionStateMachine(
          auction2Cid,
          "accept_bid",
          MapValue(Map("timestamp" -> IntValue(8000000)))
        )
        proof30 <- registry.generateProofs(update30, Set(Victor))
        state31 <- combiner.insert(state30, Signed(update30, proof30))

        // ========================================
        // PHASE 12: ADDITIONAL MANUFACTURER CYCLES
        // ========================================

        // Event 31: Dave (new manufacturer) initialization and production
        daveRetailerDef <- decodeStateMachine(manufacturerStateMachineJson())
        daveData = manufacturerInitialData.copy(value =
          manufacturerInitialData.value + ("businessName" -> StrValue("TextileWorks Co"))
        )
        daveHash <- (daveData: JsonLogicValue).computeDigest
        daveFiber = Records.StateMachineFiberRecord(
          cid = daveCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = daveRetailerDef,
          currentState = StateId("idle"),
          stateData = daveData,
          stateDataHash = daveHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Dave).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Add Dave to the state
        state32 <- state31.withRecord[IO](daveCid, daveFiber)

        // Event 32: Dave schedules production
        update32 = Updates.TransitionStateMachine(
          daveCid,
          "schedule_production",
          MapValue(
            Map(
              "timestamp" -> IntValue(8100000),
              "batchSize" -> IntValue(150)
            )
          )
        )
        proof32 <- registry.generateProofs(update32, Set(Dave))
        state33 <- combiner.insert(state32, Signed(update32, proof32))

        // Event 33: Dave starts production
        update33 = Updates.TransitionStateMachine(
          daveCid,
          "start_production",
          MapValue(Map("timestamp" -> IntValue(8200000)))
        )
        proof33 <- registry.generateProofs(update33, Set(Dave))
        state34 <- combiner.insert(state33, Signed(update33, proof33))

        // Event 34: Dave completes production
        update34 = Updates.TransitionStateMachine(
          daveCid,
          "complete_batch",
          MapValue(Map("timestamp" -> IntValue(8300000)))
        )
        proof34 <- registry.generateProofs(update34, Set(Dave))
        state35 <- combiner.insert(state34, Signed(update34, proof34))

        // ========================================
        // PHASE 13: IVAN RETAILER (DEPENDS ON DAVE)
        // ========================================

        // Event 35: Ivan retailer initialization
        ivanRetailerDef <- decodeStateMachine(retailerStateMachineJson(daveCid.toString, niajCid.toString))
        ivanData = retailerInitialData.copy(value =
          retailerInitialData.value + ("businessName" -> StrValue("StyleHub Boutique"))
        )
        ivanHash <- (ivanData: JsonLogicValue).computeDigest
        ivanFiber = Records.StateMachineFiberRecord(
          cid = ivanCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = ivanRetailerDef,
          currentState = StateId("open"),
          stateData = ivanData,
          stateDataHash = ivanHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Ivan).map(registry.addresses),
          status = FiberStatus.Active
        )

        // Add Ivan to the state
        state36 <- state35.withRecord[IO](ivanCid, ivanFiber)

        // Event 36: Ivan checks inventory (triggers order to Dave)
        update36 = Updates.TransitionStateMachine(
          ivanCid,
          "check_inventory",
          MapValue(
            Map(
              "timestamp"  -> IntValue(8400000),
              "shipmentId" -> StrValue("SHIP-003")
            )
          )
        )
        proof36 <- registry.generateProofs(update36, Set(Ivan))
        state37 <- combiner.insert(state36, Signed(update36, proof36))

        // ========================================
        // PHASE 14: CONSUMER SERVICE PROVISION
        // ========================================

        // Event 37: Ruth provides freelance service (earns income)
        update37 = Updates.TransitionStateMachine(
          ruthCid,
          "provide_service",
          MapValue(
            Map(
              "timestamp"        -> IntValue(8500000),
              "requestedService" -> StrValue("software_development"),
              "payment"          -> IntValue(3000)
            )
          )
        )
        proof37 <- registry.generateProofs(update37, Set(Ruth))
        state38 <- combiner.insert(state37, Signed(update37, proof37))

        // ========================================
        // PHASE 15: MORE CONSUMER SHOPPING
        // ========================================

        // Event 38: Victor shops at Grace's FreshFoods Market
        update38 = Updates.TransitionStateMachine(
          victorCid,
          "browse_products",
          MapValue(
            Map(
              "timestamp"    -> IntValue(8600000),
              "retailerId"   -> StrValue(graceCid.toString),
              "expectedCost" -> IntValue(100),
              "quantity"     -> IntValue(10)
            )
          )
        )
        proof38 <- registry.generateProofs(update38, Set(Victor))
        state39 <- combiner.insert(state38, Signed(update38, proof38))

        // Event 39: Grace reopens after restocking
        update39 = Updates.TransitionStateMachine(
          graceCid,
          "reopen",
          MapValue(Map("timestamp" -> IntValue(8650000)))
        )
        proof39 <- registry.generateProofs(update39, Set(Grace))
        state40 <- combiner.insert(state39, Signed(update39, proof39))

        // Event 40: Ivan reopens after restocking from Dave
        update40 = Updates.TransitionStateMachine(
          ivanCid,
          "reopen",
          MapValue(Map("timestamp" -> IntValue(8700000)))
        )
        proof40 <- registry.generateProofs(update40, Set(Ivan))
        state41 <- combiner.insert(state40, Signed(update40, proof40))

        // ========================================
        // PHASE 16: ADDITIONAL ECONOMIC ACTIVITY
        // ========================================

        // Event 41: Alice schedules another production batch
        update41 = Updates.TransitionStateMachine(
          aliceCid,
          "schedule_production",
          MapValue(
            Map(
              "timestamp" -> IntValue(8800000),
              "batchSize" -> IntValue(80)
            )
          )
        )
        proof41 <- registry.generateProofs(update41, Set(Alice))
        state42 <- combiner.insert(state41, Signed(update41, proof41))

        // Event 42: Alice starts second production run
        update42 = Updates.TransitionStateMachine(
          aliceCid,
          "start_production",
          MapValue(Map("timestamp" -> IntValue(8900000)))
        )
        proof42 <- registry.generateProofs(update42, Set(Alice))
        state43 <- combiner.insert(state42, Signed(update42, proof42))

        // Event 43: Alice completes second batch
        update43 = Updates.TransitionStateMachine(
          aliceCid,
          "complete_batch",
          MapValue(Map("timestamp" -> IntValue(9000000)))
        )
        proof43 <- registry.generateProofs(update43, Set(Alice))
        state44 <- combiner.insert(state43, Signed(update43, proof43))

        // ========================================
        // PHASE 17: TAX COLLECTION
        // ========================================

        // Instead of broadcasting to all 10 taxpayers at once (which would exceed execution depth),
        // we send individual pay_taxes events to each taxpayer.

        taxPayload = MapValue(
          Map(
            "taxPeriod"      -> StrValue("Q4-2024"),
            "taxRate"        -> FloatValue(0.05),
            "collectionDate" -> IntValue(9100000),
            "governanceId"   -> StrValue(xavierCid.toString)
          )
        )

        // Event 44: Alice pays taxes
        update44 = Updates.TransitionStateMachine(aliceCid, "pay_taxes", taxPayload)
        proof44 <- registry.generateProofs(update44, Set(Alice))
        state45 <- combiner.insert(state44, Signed(update44, proof44))

        // Event 45: Bob pays taxes
        update45 = Updates.TransitionStateMachine(bobCid, "pay_taxes", taxPayload)
        proof45 <- registry.generateProofs(update45, Set(Bob))
        state46 <- combiner.insert(state45, Signed(update45, proof45))

        // Event 46: Charlie pays taxes
        update46 = Updates.TransitionStateMachine(charlieCid, "pay_taxes", taxPayload)
        proof46 <- registry.generateProofs(update46, Set(Charlie))
        state47 <- combiner.insert(state46, Signed(update46, proof46))

        // Event 47: Dave pays taxes
        update47 = Updates.TransitionStateMachine(daveCid, "pay_taxes", taxPayload)
        proof47 <- registry.generateProofs(update47, Set(Dave))
        state48 <- combiner.insert(state47, Signed(update47, proof47))

        // Event 48: Heidi pays taxes
        update48 = Updates.TransitionStateMachine(heidiCid, "pay_taxes", taxPayload)
        proof48 <- registry.generateProofs(update48, Set(Heidi))
        state49 <- combiner.insert(state48, Signed(update48, proof48))

        // Event 49: Grace pays taxes
        update49 = Updates.TransitionStateMachine(graceCid, "pay_taxes", taxPayload)
        proof49 <- registry.generateProofs(update49, Set(Grace))
        state50 <- combiner.insert(state49, Signed(update49, proof49))

        // Event 50: Ivan pays taxes
        update50 = Updates.TransitionStateMachine(ivanCid, "pay_taxes", taxPayload)
        proof50 <- registry.generateProofs(update50, Set(Ivan))
        state51 <- combiner.insert(state50, Signed(update50, proof50))

        // Event 51: Ruth pays taxes
        update51 = Updates.TransitionStateMachine(ruthCid, "pay_taxes", taxPayload)
        proof51 <- registry.generateProofs(update51, Set(Ruth))
        state52 <- combiner.insert(state51, Signed(update51, proof51))

        // Event 52: Sybil pays taxes
        update52 = Updates.TransitionStateMachine(sybilCid, "pay_taxes", taxPayload)
        proof52 <- registry.generateProofs(update52, Set(Sybil))
        state53 <- combiner.insert(state52, Signed(update52, proof52))

        // Event 53: Victor pays taxes
        update53 = Updates.TransitionStateMachine(victorCid, "pay_taxes", taxPayload)
        proof53 <- registry.generateProofs(update53, Set(Victor))
        state54 <- combiner.insert(state53, Signed(update53, proof53))

        // ========================================
        // FINAL VALIDATIONS
        // ========================================

        // Extract final states for validation (use state54 to include all tax payments)
        finalAliceFiber = state54.calculated.stateMachines
          .get(aliceCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalBobFiber = state54.calculated.stateMachines
          .get(bobCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalCharlieFiber = state54.calculated.stateMachines
          .get(charlieCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalDaveFiber = state54.calculated.stateMachines
          .get(daveCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalGraceFiber = state54.calculated.stateMachines
          .get(graceCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalHeidiFiber = state54.calculated.stateMachines
          .get(heidiCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalIvanFiber = state54.calculated.stateMachines
          .get(ivanCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalOscarFiber = state54.calculated.stateMachines
          .get(oscarCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalPeggyFiber = state54.calculated.stateMachines
          .get(peggyCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalQuentinFiber = state54.calculated.stateMachines
          .get(quentinCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalRuthFiber = state54.calculated.stateMachines
          .get(ruthCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalSybilFiber = state54.calculated.stateMachines
          .get(sybilCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalVictorFiber = state54.calculated.stateMachines
          .get(victorCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalYolandaFiber = state54.calculated.stateMachines
          .get(yolandaCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Extract auction states
        finalAuction1Fiber = state54.calculated.stateMachines
          .get(auctionCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        finalAuction2Fiber = state54.calculated.stateMachines
          .get(auction2Cid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Extract intermediate states for Ruth's payment
        ruthAfterPayment = state28.calculated.stateMachines
          .get(ruthCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        oscarAfterPayment = state28.calculated.stateMachines
          .get(oscarCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Extract states after Ruth's service provision
        ruthAfterService = state38.calculated.stateMachines
          .get(ruthCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Extract states for Ivan and Dave supply chain
        ivanAfterInventoryCheck = state37.calculated.stateMachines
          .get(ivanCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        daveAfterFulfillment = state37.calculated.stateMachines
          .get(daveCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Extract Xavier's final state after tax collection
        finalXavierFiber = state54.calculated.stateMachines
          .get(xavierCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // Extract Niaj's final state
        finalNiajFiber = state54.calculated.stateMachines
          .get(niajCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect.all(
        // Verify all participants exist
        finalAliceFiber.isDefined,
        finalHeidiFiber.isDefined,
        finalOscarFiber.isDefined,
        finalRuthFiber.isDefined,
        finalCharlieFiber.isDefined,
        finalYolandaFiber.isDefined,

        // ========================================
        // NIAJ (PLATFORM) COMPREHENSIVE VALIDATIONS
        // ========================================

        // Verify Niaj (Platform) exists
        finalNiajFiber.isDefined,

        // Verify Niaj remained in idle state (no events processed)
        finalNiajFiber.map(_.currentState).contains(StateId("idle")),

        // Verify Niaj's sequence number is 0 (passive participant, no events)
        finalNiajFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.MinValue),

        // Verify Niaj's transaction count is still 0
        finalNiajFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("transactions").collect { case IntValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 0),

        // Verify Niaj's status is still active
        finalNiajFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("active"),

        // Verify state transitions
        finalAliceFiber.map(_.sequenceNumber).exists(_ > FiberOrdinal.MinValue),
        finalOscarFiber
          .map(_.currentState)
          .contains(
            StateId("loan_servicing")
          ), // Oscar underwrote the loan successfully (Fed was stable), then completed stress test
        finalCharlieFiber.map(_.currentState).contains(StateId("supply_shortage")),
        finalYolandaFiber
          .map(_.currentState)
          .contains(StateId("stress_testing")), // Fed is in stress_testing state

        // Verify Ruth received loan funding and is in debt_current state
        finalRuthFiber.map(_.currentState).contains(StateId("debt_current")),

        // Verify cross-machine triggers fired - Heidi goes from open -> inventory_low -> stocking in same snapshot
        heidiFiberAfterCheck.map(_.currentState).contains(StateId("stocking")),

        // Verify Charlie's supply shortage was detected
        charlieFiberAfterShortage.map(_.currentState).contains(StateId("supply_shortage")),

        // Verify Bob's production cycle
        finalBobFiber.map(_.currentState).contains(StateId("shipping")), // Bob fulfilled Grace's order
        finalBobFiber
          .map(_.sequenceNumber)
          .exists(_ == FiberOrdinal.unsafeApply(5L)), // 5 events: schedule, start, complete, fulfill_order, pay_taxes
        finalBobFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("inventory").collect { case IntValue(i) => i }
              case _           => None
            }
          }
          .exists(_ == 400), // 500 - 100 = 400 (after fulfilling order)

        // Verify Grace cross-chain order
        finalGraceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("stock").collect { case IntValue(s) => s }
              case _           => None
            }
          }
          .exists(_ == 105), // 15 (initial) + 100 (restock from Bob) - 10 (sold to Victor while stocking) = 105

        // Verify Ruth's shopping
        finalRuthFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("purchaseCount").collect { case IntValue(c) => c }
              case _           => None
            }
          }
          .exists(_ == 1), // Ruth made 1 purchase

        // Ruth's balance: 5000 + 10000 (loan) - 50 (purchase) - 300 (payment) + 3000 (service) = 17650
        finalRuthFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("balance").collect { case IntValue(b) => b }
              case _           => None
            }
          }
          .exists(_ == 17650),

        // Verify Heidi processed the sale
        finalHeidiFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("salesCount").collect { case IntValue(c) => c }
              case _           => None
            }
          }
          .exists(_ >= 1), // Heidi made at least 1 sale

        // Heidi's revenue increased (5 items * 10 per unit = 50)
        finalHeidiFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("revenue").collect { case IntValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 50),

        // Heidi's stock decreased (stock after restocking from Alice, then sold 5 to Ruth)
        finalHeidiFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("stock").collect { case IntValue(s) => s }
              case _           => None
            }
          }
          .exists(_ == 110), // 115 (after restocking) - 5 (sold to Ruth) = 110

        // Verify Peggy and Quentin banks received rate_adjustment from Fed (Event 2)
        finalPeggyFiber.map(_.sequenceNumber).exists(_ >= FiberOrdinal.MinValue.next), // Peggy received rate_adjustment
        finalPeggyFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("baseRate").collect { case FloatValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 0.0425), // 0.04 + 0.0025 = 0.0425 (after rate increase)

        finalQuentinFiber
          .map(_.sequenceNumber)
          .exists(_ >= FiberOrdinal.MinValue.next), // Quentin received rate_adjustment
        finalQuentinFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("baseRate").collect { case FloatValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 0.0425), // 0.04 + 0.0025 = 0.0425 (after rate increase)

        // Verify Sybil's delinquency
        finalSybilFiber.map(_.currentState).contains(StateId("debt_delinquent")),
        finalSybilFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("missedPayments").collect { case IntValue(mp) => mp }
              case _           => None
            }
          }
          .exists(_ >= 1), // Sybil missed at least 1 payment

        // Verify stress test execution - banks should have completed stress test and returned to original states
        yolandaFiberAfterStressTest
          .map(_.currentState)
          .contains(StateId("stress_testing")), // Fed is still in stress_testing state
        oscarFiberAfterStressTest
          .map(_.currentState)
          .contains(StateId("loan_servicing")), // Oscar returned to loan_servicing (has activeLoans > 0)
        peggyFiberAfterStressTest
          .map(_.currentState)
          .contains(StateId("loan_servicing")), // Peggy returned to loan_servicing (has activeLoans > 0)
        quentinFiberAfterStressTest
          .map(_.currentState)
          .contains(StateId("operating")), // Quentin returned to operating (has activeLoans == 0)

        // Verify banks passed the stress test
        oscarFiberAfterStressTest
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("stressTestPassed").collect { case BoolValue(p) => p }
              case _           => None
            }
          }
          .getOrElse(false),
        peggyFiberAfterStressTest
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("stressTestPassed").collect { case BoolValue(p) => p }
              case _           => None
            }
          }
          .getOrElse(false),
        quentinFiberAfterStressTest
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("stressTestPassed").collect { case BoolValue(p) => p }
              case _           => None
            }
          }
          .getOrElse(false),

        // Verify Heidi's holiday sale
        heidiFiberAfterSale
          .map(_.currentState)
          .contains(StateId("sale_active")), // Heidi entered sale_active state
        heidiFiberAfterSale
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("discountRate").collect { case FloatValue(d) => d }
              case _           => None
            }
          }
          .exists(_ == 0.20), // 20% discount rate

        // ========================================
        // C2C MARKETPLACE VALIDATIONS
        // ========================================

        // Verify auction child machine was spawned and exists
        auctionFiberAfterBidAccepted.isDefined,

        // Verify auction reached "sold" final state
        auctionFiberAfterBidAccepted.map(_.currentState).contains(StateId("sold")),

        // Verify auction has correct parent relationship
        auctionFiberAfterBidAccepted.flatMap(_.parentFiberId).contains(sybilCid),

        // Verify auction recorded Victor as highest bidder
        auctionFiberAfterBidAccepted
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("highestBidder").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains(victorCid.toString),

        // Verify auction recorded winning bid amount (750)
        auctionFiberAfterBidAccepted
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("highestBid").collect { case IntValue(b) => b }
              case _           => None
            }
          }
          .exists(_ == 750),

        // Verify auction recorded item name
        auctionFiberAfterBidAccepted
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("itemName").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Handmade Ceramic Vase"),

        // Verify Sybil remained in "debt_delinquent" state after sale completed (delinquency doesn't clear just from earning money)
        sybilFiberAfterSale.map(_.currentState).contains(StateId("debt_delinquent")),

        // Verify Sybil's balance increased by bid amount (8000 after loan + 750 sale = 8750)
        sybilFiberAfterSale
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("balance").collect { case IntValue(b) => b }
              case _           => None
            }
          }
          .exists(_ == 8750),

        // Verify Sybil's marketplaceSales counter incremented
        sybilFiberAfterSale
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("marketplaceSales").collect { case IntValue(s) => s }
              case _           => None
            }
          }
          .exists(_ == 1),

        // Verify Sybil's activeListings decreased back to 0
        sybilFiberAfterSale
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("activeListings").collect { case IntValue(a) => a }
              case _           => None
            }
          }
          .exists(_ == 0),

        // Verify Victor still exists in active state
        finalVictorFiber.isDefined,
        finalVictorFiber.map(_.currentState).contains(StateId("active")),

        // ========================================
        // AUCTION 2 (VICTOR'S AUCTION) VALIDATIONS
        // ========================================

        // Verify Victor's auction child machine was spawned and exists
        finalAuction2Fiber.isDefined,

        // Verify auction reached "sold" final state
        finalAuction2Fiber.map(_.currentState).contains(StateId("sold")),

        // Verify auction has correct parent relationship (Victor)
        finalAuction2Fiber.flatMap(_.parentFiberId).contains(victorCid),

        // Verify auction recorded Ruth as highest bidder
        finalAuction2Fiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("highestBidder").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains(ruthCid.toString),

        // Verify auction recorded winning bid amount (2500)
        finalAuction2Fiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("highestBid").collect { case IntValue(b) => b }
              case _           => None
            }
          }
          .exists(_ == 2500),

        // Verify auction recorded item name
        finalAuction2Fiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("itemName").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Vintage Watch Collection"),

        // Verify auction reserve price was met
        finalAuction2Fiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("reservePrice").collect { case IntValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 2000),

        // ========================================
        // STATE44 VALIDATIONS (Alice's Second Production Run)
        // ========================================

        // Verify Alice completed second production cycle and reached inventory_full state
        finalAliceFiber.map(_.currentState).contains(StateId("inventory_full")),

        // Verify Alice's sequence number increased by 3 (schedule, start, complete)
        finalAliceFiber.map(_.sequenceNumber).exists(_ >= FiberOrdinal.unsafeApply(3L)),

        // Verify Alice's raw materials: 1000 - 50 (first cycle) - 50 (second cycle) = 900
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("rawMaterials").collect { case IntValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 900),

        // Verify Alice's inventory: 420 + 100 (first batch) - 100 (shipped to Heidi) + 80 (second batch) = 500
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("inventory").collect { case IntValue(i) => i }
              case _           => None
            }
          }
          .exists(_ == 500),

        // Verify Alice's total produced: 100 (first batch) + 80 (second batch) = 180
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("totalProduced").collect { case IntValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 180),

        // Verify Alice's batchSize was set correctly in schedule_production event
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("batchSize").collect { case IntValue(b) => b }
              case _           => None
            }
          }
          .exists(_ == 80),

        // Verify Alice reached max inventory capacity
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) =>
                for {
                  inventory    <- m.get("inventory").collect { case IntValue(i) => i }
                  maxInventory <- m.get("maxInventory").collect { case IntValue(m) => m }
                } yield inventory >= maxInventory
              case _ => None
            }
          }
          .getOrElse(false),

        // ========================================
        // XAVIER GOVERNANCE TAX COLLECTION VALIDATIONS
        // ========================================

        // Verify Xavier exists
        finalXavierFiber.isDefined,

        // Verify Xavier returned to monitoring state after all taxpayers paid
        finalXavierFiber.map(_.currentState).contains(StateId("monitoring")),

        // Verify Xavier received tax payments (sequence number incremented)
        finalXavierFiber.map(_.sequenceNumber).exists(_ > FiberOrdinal.MinValue),

        // Verify Xavier collected taxes from all 10 taxpayers
        finalXavierFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxpayersCompliant").collect { case IntValue(c) => c }
              case _           => None
            }
          }
          .exists(_ == 10),

        // Verify Xavier's total tax collection is correct (9 + 4 + 0 + 7.5 + 2.5 + 5 + 0 + 150 + 0 + 0 = 178)
        finalXavierFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("totalTaxesCollected").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 178.0),

        // Verify Alice processed the pay_taxes trigger and paid taxes
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ > 0.0),

        // Verify Alice's last tax payment was calculated correctly (180 * 0.05 = 9.0)
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPayment").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 9.0),

        // ========================================
        // VERIFY ALL 10 TAXPAYERS PAID TAXES
        // ========================================

        // Manufacturers (Alice, Bob, Charlie, Dave) - tax based on totalProduced
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 9.0), // 180 * 0.05 = 9.0

        finalBobFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 4.0), // 80 * 0.05 = 4.0

        finalCharlieFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Charlie didn't produce due to material shortage)

        // ========================================
        // CHARLIE (MANUFACTURER) COMPREHENSIVE VALIDATIONS
        // ========================================

        // Verify Charlie exists
        finalCharlieFiber.isDefined,

        // Verify Charlie is in supply_shortage state (insufficient raw materials)
        finalCharlieFiber.map(_.currentState).contains(StateId("supply_shortage")),

        // Verify Charlie's sequence number (check_materials, pay_taxes)
        finalCharlieFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(2L)),

        // Verify Charlie's raw materials unchanged (40 - insufficient for production)
        finalCharlieFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("rawMaterials").collect { case IntValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 40),

        // Verify Charlie's totalProduced is still 0 (no production due to shortage)
        finalCharlieFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("totalProduced").collect { case IntValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 0),

        // Verify Charlie's inventory unchanged (420 - no production occurred)
        finalCharlieFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("inventory").collect { case IntValue(i) => i }
              case _           => None
            }
          }
          .exists(_ == 420),

        // Verify Charlie's lastTaxPeriod was set
        finalCharlieFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),
        finalDaveFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 7.5), // 150 * 0.05 = 7.5 (now with full decimal precision!)

        // ========================================
        // DAVE (MANUFACTURER) COMPREHENSIVE VALIDATIONS
        // ========================================

        // Verify Dave exists
        finalDaveFiber.isDefined,

        // Verify Dave completed production cycle (schedule -> start -> complete)
        finalDaveFiber.map(_.currentState).contains(StateId("shipping")),

        // Verify Dave's sequence number reflects all events (schedule, start, complete, fulfill_order, pay_taxes)
        finalDaveFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(5L)),

        // Verify Dave's inventory: 420 (initial) + 150 (produced) - 100 (shipped to Ivan) = 470
        finalDaveFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("inventory").collect { case IntValue(i) => i }
              case _           => None
            }
          }
          .exists(_ == 470),

        // Verify Dave's totalProduced matches batchSize
        finalDaveFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("totalProduced").collect { case IntValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 150),

        // Verify Dave consumed raw materials: 1000 (initial) - 50 (required for production) = 950
        finalDaveFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("rawMaterials").collect { case IntValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 950),

        // Verify Dave fulfilled Ivan's order (shipmentCount incremented)
        finalDaveFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("shipmentCount").collect { case IntValue(s) => s }
              case _           => None
            }
          }
          .exists(_ == 1),

        // Verify Dave's lastTaxPeriod was set
        finalDaveFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),

        // Retailers (Heidi, Grace, Ivan) - tax based on revenue
        finalHeidiFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 2.5), // 50 * 0.05 = 2.5 (now with full decimal precision!)

        finalGraceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 5.0), // 100 * 0.05 = 5.0

        // ========================================
        // GRACE (RETAILER) COMPREHENSIVE VALIDATIONS
        // ========================================

        // Verify Grace exists
        finalGraceFiber.isDefined,

        // Verify Grace returned to open state after restocking
        finalGraceFiber.map(_.currentState).contains(StateId("open")),

        // Verify Grace's sequence number (check_inventory, receive_shipment, reopen, process_sale, pay_taxes)
        finalGraceFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(5L)),

        // Verify Grace's revenue from Victor's purchase (10 items * 10 per unit = 100)
        finalGraceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("revenue").collect { case IntValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 100),

        // Verify Grace's salesCount incremented
        finalGraceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("salesCount").collect { case IntValue(s) => s }
              case _           => None
            }
          }
          .exists(_ == 1),

        // Verify Grace's lastTaxPeriod was set
        finalGraceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),
        finalIvanFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Ivan has no revenue yet)

        // ========================================
        // IVAN (RETAILER) COMPREHENSIVE VALIDATIONS
        // ========================================

        // Verify Ivan exists
        finalIvanFiber.isDefined,

        // Verify Ivan returned to open state after restocking
        finalIvanFiber.map(_.currentState).contains(StateId("open")),

        // Verify Ivan's sequence number (check_inventory, receive_shipment, reopen, pay_taxes)
        finalIvanFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(4L)),

        // Verify Ivan's stock after restocking from Dave (15 initial + 100 restock = 115)
        finalIvanFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("stock").collect { case IntValue(s) => s }
              case _           => None
            }
          }
          .exists(_ == 115),

        // Verify Ivan has zero revenue (no sales yet)
        finalIvanFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("revenue").collect { case IntValue(r) => r }
              case _           => None
            }
          }
          .exists(_ == 0),

        // Verify Ivan has zero sales count
        finalIvanFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("salesCount").collect { case IntValue(s) => s }
              case _           => None
            }
          }
          .exists(_ == 0),

        // Verify Ivan's lastTaxPeriod was set
        finalIvanFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),

        // Consumers (Ruth, Sybil, Victor) - tax based on serviceRevenue
        finalRuthFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 150.0), // 3000 * 0.05 = 150.0

        finalSybilFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Sybil has no service revenue)

        finalVictorFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
              case _           => None
            }
          }
          .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Victor has no service revenue)

        // Verify all taxpayers have the lastTaxPeriod field set
        finalAliceFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),
        finalBobFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),
        finalRuthFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),

        // Verify remaining taxpayers have lastTaxPeriod set
        finalHeidiFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),
        finalSybilFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024"),
        finalVictorFiber
          .flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("lastTaxPeriod").collect { case StrValue(s) => s }
              case _           => None
            }
          }
          .contains("Q4-2024")
      )
    }
  }
}
