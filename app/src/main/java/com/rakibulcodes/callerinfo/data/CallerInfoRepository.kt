package com.rakibulcodes.callerinfo.data

import android.content.Context
import android.util.Log
import com.rakibulcodes.callerinfo.data.database.AppDatabase
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CallerInfoRepository(private val context: Context) {
    private val gson = Gson()
    private val db = AppDatabase.getDatabase(context)
    private val telegramManager = TelegramManager.getInstance(context)

    suspend fun getCallerInfo(rawNumber: String): CallerInfoEntity {
        val number = sanitizeNumber(rawNumber)
        if (number.isEmpty()) {
            return errorEntity(rawNumber.trim(), "Invalid Number")
        }
        
        val cached = db.callerInfoDao().getCallerInfo(number)
        if (cached != null && cached.name != "Unknown") {
            return cached
        }

        if (!isNetworkAvailable()) {
            return errorEntity(number, "No internet connection")
        }

        return fetchFromTelegram(number)
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun getAllHistory(): List<CallerInfoEntity> {
        return db.callerInfoDao().getAllCallerInfo()
    }

    suspend fun clearHistory() {
        db.callerInfoDao().clearAll()
    }

    suspend fun deleteHistoryItem(number: String) {
        db.callerInfoDao().deleteByNumber(number)
    }

    fun sanitizeNumber(input: String): String {
        // 1. Strip everything except digits and '+', ensuring only one leading '+' survives
        var digits = input.replace(Regex("[^0-9+]"), "")
        if (digits.isEmpty()) return ""
        
        val hasLeadingPlus = digits.startsWith("+")
        digits = digits.replace("+", "")
        if (hasLeadingPlus) digits = "+$digits"

        // 2. Convert standard international dialing codes to '+'
        when {
            digits.startsWith("00") -> digits = "+" + digits.substring(2)
            digits.startsWith("011") -> digits = "+" + digits.substring(3)
        }

        // 3. Smart Default to Bangladesh format for non-international inputs
        if (!digits.startsWith("+")) {
            digits = when {
                digits.startsWith("0880") -> "+" + digits.substring(1) // Catches '0880' typo
                digits.startsWith("880") -> "+$digits"                 // Catches missing '+'
                digits.startsWith("0") && digits.length == 11 -> "+88$digits" // Standard local (017...)
                digits.length == 10 -> "+880$digits"                   // Missing '0' (17...)
                else -> digits
            }
        }

        // 4. Final Validation
        return when {
            // Valid BD: Must be exactly 14 chars (+880 + 10 digits), and the 5th char (index 4) must be 1-9
            digits.startsWith("+880") -> {
                if (digits.length == 14 && digits[4] in '1'..'9') digits else ""
            }
            // Valid International: Starts with '+' followed by 7 to 15 digits (Total length 8 to 16)
            digits.startsWith("+") -> {
                if (digits.length in 8..16) digits else ""
            }
            // Unrecognized format
            else -> ""
        }
    }

    private suspend fun fetchFromTelegram(number: String): CallerInfoEntity = withContext(Dispatchers.IO) {
        if (!telegramManager.isReady()) {
            return@withContext errorEntity(number, "Telegram not connected")
        }

        // --- PRE-FLIGHT CHECK ---
        // 1. Join and mute the true_caller group
        telegramManager.ensureJoined("true_caller", isBot = false)
        
        // 2. Search, unblock, start, and mute the TrueCalleRobot
        telegramManager.ensureJoined("TrueCalleRobot", isBot = true)
        // ------------------------

        try {
            val botUsername = "TrueCalleRobot"
            val searchResult = telegramManager.sendSuspend(TdApi.SearchPublicChat(botUsername))
            if (searchResult !is TdApi.Chat) {
                return@withContext errorEntity(number, "Bot not found")
            }

            val chatId = searchResult.id

            val content = TdApi.InputMessageText(TdApi.FormattedText(number, null), null, true)
            val sentMsgId = try {
                val msg = telegramManager.sendSuspend(TdApi.SendMessage(chatId, null, null, null, null, content))
                (msg as? TdApi.Message)?.id ?: 0L
            } catch (e: Exception) { 0L }

            var result: CallerInfoEntity? = null
            
            // Wait for update instantly using SharedFlow
            val update = withTimeoutOrNull(30000) {
                telegramManager.updates.first { obj ->
                    when (obj) {
                        is TdApi.UpdateNewMessage -> {
                            val msg = obj.message
                            if (msg.chatId == chatId && !msg.isOutgoing && msg.id > sentMsgId) {
                                val textObj = extractFormattedText(msg.content)
                                if (textObj != null) {
                                    val txt = textObj.text
                                    !txt.contains("Searching...") && (
                                        txt.contains("Country:", true) ||
                                            txt.contains("Not Found", true) ||
                                            txt.contains("exceeded", true) ||
                                            txt.contains("Says:", true) ||
                                            txt.contains("Name:", true) ||
                                            txt.contains("invalid number", true) ||
                                            txt.contains("Oops", true)
                                        )
                                } else false
                            } else false
                        }
                        is TdApi.UpdateMessageContent -> {
                            if (obj.chatId == chatId && obj.messageId > sentMsgId) {
                                val textObj = extractFormattedText(obj.newContent)
                                if (textObj != null) {
                                    val txt = textObj.text
                                    !txt.contains("Searching...") && (
                                        txt.contains("Country:", true) ||
                                            txt.contains("Not Found", true) ||
                                            txt.contains("exceeded", true) ||
                                            txt.contains("Says:", true) ||
                                            txt.contains("Name:", true) ||
                                            txt.contains("invalid number", true) ||
                                            txt.contains("Oops", true)
                                        )
                                } else false
                            } else false
                        }
                        else -> false
                    }
                }
            }
            
            if (update != null) {
                val textObj = when (update) {
                    is TdApi.UpdateNewMessage -> extractFormattedText(update.message.content)
                    is TdApi.UpdateMessageContent -> extractFormattedText(update.newContent)
                    else -> null
                }
                
                if (textObj != null) {
                    result = parseBotResponse(number, textObj)
                }
            }

            val finalResult = result ?: errorEntity(number, "No response from bot")
            
            if (finalResult.error == null || finalResult.error == "Internal Processing Error") {
                val prefs = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
                val limitStr = prefs.getString("max_history_size", "1000")
                if (limitStr == "Unlimited") {
                    db.callerInfoDao().insertCallerInfo(finalResult)
                } else {
                    val limit = limitStr?.toIntOrNull() ?: 1000
                    db.callerInfoDao().insertAndTrim(finalResult, limit)
                }
            }
            finalResult

        } catch (e: Exception) {
            errorEntity(number, "Error: ${e.message}")
        }
    }

    private fun extractFormattedText(content: TdApi.MessageContent): TdApi.FormattedText? {
        return when (content) {
            is TdApi.MessageText -> content.text
            is TdApi.MessagePhoto -> content.caption
            is TdApi.MessageVideo -> content.caption
            is TdApi.MessageAnimation -> content.caption
            is TdApi.MessageDocument -> content.caption
            else -> null
        }
    }

    private fun TdApi.FormattedText.toHtml(): String {
        val insertions = mutableListOf<Pair<Int, String>>()
        if (entities != null) {
            for (entity in entities) {
                val tag = when (entity.type) {
                    is TdApi.TextEntityTypeBold -> "strong"
                    is TdApi.TextEntityTypeCode -> "code"
                    else -> null
                }
                if (tag != null) {
                    insertions.add(Pair(entity.offset, "<$tag>"))
                    insertions.add(Pair(entity.offset + entity.length, "</$tag>"))
                }
            }
        }
        insertions.sortByDescending { it.first }
        var resultText = text
        for ((offset, tagStr) in insertions) {
            if (offset in 0..resultText.length) {
                resultText = resultText.substring(0, offset) + tagStr + resultText.substring(offset)
            }
        }
        return resultText
    }

    private fun parseBotResponse(number: String, formattedText: TdApi.FormattedText): CallerInfoEntity {
        val responseText = formattedText.toHtml()
        
        var error: String? = "Internal Processing Error"
        
        if (responseText.contains("invalid number", ignoreCase = true) || responseText.contains("Oops", ignoreCase = true)) {
            error = "Invalid Number"
        } else if (responseText.contains("exceeded your daily search limit", ignoreCase = true)) {
            val timeMatch = Regex("reset in\\s+(.*?)$", RegexOption.IGNORE_CASE).find(responseText)
            error = "Daily Limit Exceeded"
            if (timeMatch != null) {
                error += ". Resets in ${timeMatch.groupValues[1].trim()}"
            }
        } else if (responseText.contains("Limit exceeded", ignoreCase = true)) {
            error = "Limit Exceeded"
        } else {
            error = null
        }

        var country: String? = null
        val countryMatch = Regex("Country:\\s*([^<]+)(?:<\\/strong>)?", RegexOption.IGNORE_CASE).find(responseText)
        if (countryMatch != null) {
            country = countryMatch.groupValues[1].trim()
        }

        val providers = mutableListOf<Pair<String, Int>>()
        val providerRegex = Regex("<strong>\\s*([^<]+?)\\s*Says:<\\/strong>")
        val providerMatches = providerRegex.findAll(responseText)
        for (match in providerMatches) {
            providers.add(Pair(match.groupValues[1].trim(), match.range.first))
        }

        val names = mutableSetOf<String>()
        var carrier: String? = null
        var email: String? = null
        var location: String? = null
        var address1: String? = null
        var address2: String? = null

        val kvRegex = Regex("<strong>([^<]+?):\\s*<\\/strong>\\s*<code>(.*?)<\\/code>")
        val kvMatches = kvRegex.findAll(responseText)
        for (match in kvMatches) {
            val key = match.groupValues[1].trim().lowercase().replace(Regex("\\s"), "_")
            val value = match.groupValues[2].replace(Regex("<[^>]*>?"), "").trim()
            val index = match.range.first

            if (value.isEmpty() || value.equals("Not Found", ignoreCase = true)) continue

            if (key == "name") {
                names.add(value)
            } else if (key == "carrier") {
                if (carrier == null) carrier = value
            } else if (key == "email") {
                if (email == null) email = value
            } else if (key == "location") {
                if (location == null) location = value
            } else if (key == "address1" || key == "address_1") {
                if (address1 == null) address1 = value
            } else if (key == "address2" || key == "address_2") {
                if (address2 == null) address2 = value
            }
        }

        val finalName = if (names.isNotEmpty()) names.joinToString(", ") else "Unknown"

        return CallerInfoEntity(
            number = number,
            name = finalName,
            carrier = carrier,
            country = country,
            email = email,
            location = location,
            address1 = address1,
            address2 = address2,
            error = error,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun errorEntity(number: String, msg: String) = CallerInfoEntity(
        number = number,
        country = null,
        name = null,
        carrier = null,
        email = null,
        location = null,
        address1 = null,
        address2 = null,
        error = msg
    )
    
    companion object {
        @Volatile
        private var INSTANCE: CallerInfoRepository? = null

        fun getInstance(context: Context): CallerInfoRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = CallerInfoRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
