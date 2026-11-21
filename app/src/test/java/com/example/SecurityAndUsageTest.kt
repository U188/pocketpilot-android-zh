package com.example

import com.example.data.opencode.OpenCodeConfigHelper
import com.example.data.opencode.OpenCodeJsonParser
import com.example.domain.model.CustomModelEntry
import com.example.domain.model.CustomProviderSaveRequest
import com.example.domain.model.ModelPricing
import com.example.domain.model.OpenCodeEvent
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodeModel
import com.example.domain.model.OpenCodeProvider
import com.example.domain.model.TokenUsage
import com.example.domain.model.UsageCalculator
import com.example.domain.model.UsageRecord
import com.example.security.ProfileBackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecurityAndUsageTest {

    @Test
    fun profileBackupCrypto_roundTripAndNoPlaintextLeak() {
        val secretPayload = """
            {
              "profiles": [
                {
                  "name": "Prod VPS",
                  "host": "10.0.0.42",
                  "password": "super-secret-ssh-password-999",
                  "privateKeyPem": "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET_KEY_BODY\n-----END OPENSSH PRIVATE KEY-----"
                }
              ]
            }
        """.trimIndent()

        val encrypted = ProfileBackupCrypto.encrypt(secretPayload, "MyStrongPass!1")

        assertFalse("Ciphertext must not leak SSH password", encrypted.contains("super-secret-ssh-password-999"))
        assertFalse("Ciphertext must not leak private key", encrypted.contains("SECRET_KEY_BODY"))
        assertTrue(encrypted.contains("pocketpilot-backup"))

        val decrypted = ProfileBackupCrypto.decrypt(encrypted, "MyStrongPass!1")
        assertEquals(secretPayload, decrypted)

        try {
            ProfileBackupCrypto.decrypt(encrypted, "WrongPassword")
            fail("Expected decryption with wrong password to throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun openCodeJsonParser_discoversUsageSchemaAndParsesTokensAndLimits() {
        val openApiDoc = """
            {
              "paths": {
                "/session/{id}/message": {},
                "/config/providers": {}
              },
              "components": {
                "schemas": {
                  "AssistantMessage": {
                    "type": "object",
                    "properties": {
                      "cost": { "type": "number" },
                      "tokens": {
                        "type": "object",
                        "properties": {
                          "input": { "type": "number" },
                          "output": { "type": "number" },
                          "reasoning": { "type": "number" },
                          "cache": {
                            "type": "object",
                            "properties": {
                              "read": { "type": "number" },
                              "write": { "type": "number" }
                            }
                          }
                        }
                      }
                    }
                  },
                  "Model": {
                    "type": "object",
                    "properties": {
                      "limit": {
                        "type": "object",
                        "properties": {
                          "context": { "type": "number" },
                          "output": { "type": "number" }
                        }
                      },
                      "cost": {
                        "type": "object",
                        "properties": {
                          "input": { "type": "number" },
                          "output": { "type": "number" }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val discovered = OpenCodeJsonParser.parseOpenApiDoc(openApiDoc)
        assertEquals("tokens", discovered.usageSchema.tokensField)
        assertEquals("cost", discovered.usageSchema.costField)
        assertEquals("context", discovered.usageSchema.modelContextLimitField)

        val messagesJson = """
            [
              {
                "info": {
                  "id": "msg_1",
                  "sessionID": "ses_1",
                  "role": "assistant",
                  "providerID": "custom-gw",
                  "modelID": "claude-sonnet-4",
                  "cost": 0.0125,
                  "tokens": {
                    "input": 1200,
                    "output": 350,
                    "reasoning": 50,
                    "cache": { "read": 400, "write": 100 }
                  },
                  "time": { "created": 1700000000000, "completed": 1700000005000 }
                },
                "parts": [
                  { "id": "p1", "type": "text", "text": "Готово" }
                ]
              }
            ]
        """.trimIndent()

        val messages = OpenCodeJsonParser.parseMessages(messagesJson, "ses_1", discovered.usageSchema)
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(0.0125, msg.costUsd ?: 0.0, 1e-6)
        val tokens = msg.tokens!!
        assertEquals(1200L, tokens.input)
        assertEquals(350L, tokens.output)
        assertEquals(50L, tokens.reasoning)
        assertEquals(400L, tokens.cacheRead)
        assertEquals(100L, tokens.cacheWrite)

        val providersJson = """
            {
              "providers": [
                {
                  "id": "custom-gw",
                  "name": "Custom GW",
                  "models": {
                    "claude-sonnet-4": {
                      "name": "Claude Sonnet 4",
                      "limit": { "context": 200000, "output": 8192 },
                      "cost": { "input": 3.0, "output": 15.0 }
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val providers = OpenCodeJsonParser.parseProviders(providersJson, discovered.usageSchema)
        assertEquals(1, providers.size)
        val model = providers.first().models.first()
        assertEquals(200000L, model.contextLimit)
        assertEquals(8192L, model.outputLimit)
        assertEquals(3.0, model.inputCostPer1M ?: 0.0, 1e-6)
        assertEquals(15.0, model.outputCostPer1M ?: 0.0, 1e-6)

        val ssePayload = """
            {
              "type": "message.updated",
              "properties": {
                "info": {
                  "id": "msg_2",
                  "sessionID": "ses_1",
                  "role": "assistant",
                  "providerID": "custom-gw",
                  "modelID": "claude-sonnet-4",
                  "cost": 0.0,
                  "tokens": { "input": 500, "output": 100, "cache": { "read": 50, "write": 0 } }
                }
              }
            }
        """.trimIndent()

        val sseEvent = OpenCodeJsonParser.parseSseEvent(ssePayload, discovered.usageSchema)
        assertTrue(sseEvent is OpenCodeEvent.MessageUpdated)
        val updated = (sseEvent as OpenCodeEvent.MessageUpdated).message
        assertEquals(500L, updated.tokens?.input)
        assertEquals(100L, updated.tokens?.output)
        assertEquals(50L, updated.tokens?.cacheRead)
    }

    @Test
    fun openCodeConfigHelper_writesAndReadsModelCostFields() {
        val merged = OpenCodeConfigHelper.mergeOpenAiCompatibleProvider(
            existingJson = null,
            request = CustomProviderSaveRequest(
                providerId = "my-proxy",
                providerName = "My Proxy",
                baseUrl = "https://proxy.example.com/v1",
                apiKey = "sk-test-12345678",
                selectedModels = listOf(
                    CustomModelEntry(
                        id = "deepseek-r1",
                        displayName = "DeepSeek R1",
                        selected = true,
                        multiplierText = "2.5",
                        cacheMultiplierText = "0",
                        inputPricePer1MText = "0.55",
                        outputPricePer1MText = "2.19",
                    ),
                ),
                defaultModelId = "deepseek-r1",
                replaceProviderModels = true,
            ),
        )

        val parsed = OpenCodeConfigHelper.parseConfiguredProviders(merged)
        assertEquals(1, parsed.size)
        val model = parsed.first().models.first()
        assertEquals("deepseek-r1", model.id)
        assertEquals(0.55, model.inputPricePer1M ?: 0.0, 1e-6)
        assertEquals(2.19, model.outputPricePer1M ?: 0.0, 1e-6)
    }

    @Test
    fun usageCalculator_computesMultipliersCostsContextWarningBalanceAndSafeCsv() {
        val tokens = TokenUsage(
            input = 100_000L,
            output = 50_000L,
            reasoning = 0L,
            cacheRead = 20_000L,
            cacheWrite = 10_000L,
        )

        // 1) Custom multiplier + zero cache multiplier + custom $/1M pricing
        val pricingZeroCache = ModelPricing(
            providerId = "proxy",
            modelId = "model-a",
            multiplier = 2.0,
            cacheMultiplier = 0.0,
            inputPricePer1M = 3.0,
            outputPricePer1M = 15.0,
        )
        val computed1 = UsageCalculator.computeMessageCost(tokens, serverCostUsd = 0.0, pricing = pricingZeroCache)
        // Units: (100k + 50k) * 2.0 + (20k + 10k) * 0.0 = 300_000
        assertEquals(300_000.0, computed1.providerUnits, 1e-3)
        // Cost: ((100k * 3 + 50k * 15) / 1_000_000) * 2.0 = (0.3 + 0.75) * 2.0 = 2.10
        assertEquals(2.10, computed1.currencyCostUsd ?: 0.0, 1e-6)

        // 2) Custom multiplier + cache multiplier = 1.0, no per-1M pricing, server cost = 0 -> null ("стоимость не задана")
        val pricingUnitsOnly = ModelPricing(
            providerId = "proxy",
            modelId = "model-b",
            multiplier = 1.5,
            cacheMultiplier = 1.0,
        )
        val computed2 = UsageCalculator.computeMessageCost(tokens, serverCostUsd = 0.0, pricing = pricingUnitsOnly)
        // Units: (150k * 1.5) + (30k * 1.0) = 255_000
        assertEquals(255_000.0, computed2.providerUnits, 1e-3)
        assertNull(computed2.currencyCostUsd)

        // 3) Context usage > 80% warning
        val provider = OpenCodeProvider(
            id = "proxy",
            name = "Proxy",
            models = listOf(
                OpenCodeModel(
                    providerId = "proxy",
                    providerName = "Proxy",
                    modelId = "model-a",
                    displayName = "Model A",
                    contextLimit = 100_000L,
                ),
            ),
        )
        val assistantMsg = OpenCodeMessage(
            id = "m1",
            sessionId = "s1",
            role = "assistant",
            providerId = "proxy",
            modelId = "model-a",
            tokens = TokenUsage(input = 70_000L, output = 15_000L),
        )
        val contextUsage = UsageCalculator.computeContextUsage(
            messages = listOf(assistantMsg),
            providers = listOf(provider),
            selectedProviderId = "proxy",
            selectedModelId = "model-a",
        )
        assertNotNull(contextUsage)
        assertEquals(85, contextUsage!!.percentInt)
        assertTrue(contextUsage.isWarning)

        // 4) Manual balance calculation & estimated remaining requests
        val records = listOf(
            UsageRecord(
                serverProfileId = 1L,
                workspacePath = "/srv/app",
                sessionId = "s1",
                messageId = "m1",
                providerId = "proxy",
                modelId = "model-a",
                dayKey = "2026-05-01",
                timestampMillis = 2_000L,
                inputTokens = 10_000L,
                outputTokens = 5_000L,
                reasoningTokens = 0L,
                cacheReadTokens = 0L,
                cacheWriteTokens = 0L,
                serverCostUsd = 0.0,
            ),
            UsageRecord(
                serverProfileId = 1L,
                workspacePath = "/srv/app",
                sessionId = "s1",
                messageId = "m2",
                providerId = "proxy",
                modelId = "model-a",
                dayKey = "2026-05-01",
                timestampMillis = 3_000L,
                inputTokens = 10_000L,
                outputTokens = 5_000L,
                reasoningTokens = 0L,
                cacheReadTokens = 0L,
                cacheWriteTokens = 0L,
                serverCostUsd = 0.0,
            ),
        )
        val pricingMap = mapOf(pricingZeroCache.key to pricingZeroCache)
        // Each record uses (10k + 5k) * 2.0 = 30_000 units; 2 records since enteredAt=1_000 => 60_000 spent
        val balance = UsageCalculator.computeManualBalanceSummary(
            initialBalanceUnits = 300_000.0,
            enteredAtMillis = 1_000L,
            allScopeRecords = records,
            pricingByKey = pricingMap,
        )
        assertEquals(60_000.0, balance.spentSinceEnteredUnits, 1e-3)
        assertEquals(240_000.0, balance.remainingUnits, 1e-3)
        assertEquals(30_000.0, balance.averageUnitsPerRequest ?: 0.0, 1e-3)
        assertEquals(8L, balance.estimatedRemainingRequests)

        // 5) CSV export privacy & structure check
        val csv = UsageCalculator.exportUsageToCsv(records, pricingMap)
        assertTrue(csv.startsWith("date,provider_id,model_id,requests,input_tokens,output_tokens"))
        assertTrue(csv.contains("2026-05-01,proxy,model-a,2,20000,10000"))
        assertFalse(csv.contains("/srv/app"))
    }

    @Test
    fun openCodeConfigHelper_parsesAuthFromEnvAndSystemdUnit() {
        val envFile = """
            # OpenCode server auth
            OPENCODE_SERVER_USERNAME=admin
            OPENCODE_SERVER_PASSWORD="my-secret-pass=123"
        """.trimIndent()
        val parsed = OpenCodeConfigHelper.parseOpenCodeAuthFromEnvText(envFile)
        assertNotNull(parsed)
        assertEquals("admin", parsed!!.first)
        assertEquals("my-secret-pass=123", parsed.second)

        val unitFile = """
            [Service]
            EnvironmentFile=-/etc/opencode-serve.env
            Environment="OPENCODE_SERVER_PASSWORD=inline_pass"
        """.trimIndent()
        assertEquals(listOf("/etc/opencode-serve.env"), OpenCodeConfigHelper.extractEnvironmentFilePaths(unitFile))
        val fromUnit = OpenCodeConfigHelper.parseOpenCodeAuthFromEnvText(unitFile)
        assertEquals("opencode", fromUnit?.first)
        assertEquals("inline_pass", fromUnit?.second)
    }
}
