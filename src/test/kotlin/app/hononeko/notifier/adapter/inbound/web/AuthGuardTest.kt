package app.hononeko.notifier.adapter.inbound.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthGuardTest {
    private val secret = "super-secret-token"

    @Test
    fun `should allow open access when expected token is blank`() =
        testApplication {
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, "")) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val response = client.get("/test")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `should authenticate with Bearer Authorization header`() =
        testApplication {
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, secret)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val response =
                client.get("/test") {
                    header("Authorization", "Bearer $secret")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `should authenticate with raw Authorization header`() =
        testApplication {
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, secret)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val response =
                client.get("/test") {
                    header("Authorization", secret)
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `should authenticate with X-Api-Key header`() =
        testApplication {
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, secret)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val response =
                client.get("/test") {
                    header("X-Api-Key", secret)
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `should authenticate with token query parameter`() =
        testApplication {
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, secret)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val response = client.get("/test?token=$secret")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `should authenticate with apikey query parameter`() =
        testApplication {
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, secret)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val response = client.get("/test?apikey=$secret")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `should authenticate with path parameter token`() =
        testApplication {
            application {
                routing {
                    route("/{token}") {
                        get("/test") {
                            if (AuthGuard.isAuthorized(call, secret)) {
                                call.respondText("OK")
                            } else {
                                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                            }
                        }
                    }
                }
            }

            val response = client.get("/$secret/test")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `should support multiple comma-separated authorized tokens`() =
        testApplication {
            val multiTokens = "token-alpha, token-beta, token-gamma"
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, multiTokens)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val resAlpha =
                client.get("/test") {
                    header("X-Api-Key", "token-alpha")
                }
            assertEquals(HttpStatusCode.OK, resAlpha.status)

            val resBeta =
                client.get("/test") {
                    header("X-Api-Key", "token-beta")
                }
            assertEquals(HttpStatusCode.OK, resBeta.status)

            val resWrong =
                client.get("/test") {
                    header("X-Api-Key", "token-unknown")
                }
            assertEquals(HttpStatusCode.Unauthorized, resWrong.status)
        }

    @Test
    fun `should extract instance name from query params and headers`() =
        testApplication {
            application {
                routing {
                    get("/caller") {
                        val caller = AuthGuard.extractCallerName(call) ?: "unknown"
                        call.respondText(caller)
                    }
                }
            }

            val queryInstance = client.get("/caller?instance=radarr-4k")
            assertEquals("radarr-4k", queryInstance.bodyAsText())

            val headerInstance =
                client.get("/caller") {
                    header("X-Instance-Name", "jellyfin-home")
                }
            assertEquals("jellyfin-home", headerInstance.bodyAsText())
        }

    @Test
    fun `should reject invalid token`() =
        testApplication {
            application {
                routing {
                    get("/test") {
                        if (AuthGuard.isAuthorized(call, secret)) {
                            call.respondText("OK")
                        } else {
                            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            val response = client.get("/test?token=wrong-token")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
