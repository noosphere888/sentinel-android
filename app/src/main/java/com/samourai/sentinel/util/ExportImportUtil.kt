package com.samourai.sentinel.util

import android.os.Build
import com.google.gson.reflect.TypeToken
import com.samourai.sentinel.BuildConfig
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.helpers.toJSON
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.util.CharSequenceX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject

/**
 * sentinel-android
 *
 */

class ExportImportUtil {

    private val txDao: TxDao by inject(TxDao::class.java);
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java);
    private val dojoUtil: DojoUtility by inject(DojoUtility::class.java);


    fun makePayload(): JSONObject {
        return JSONObject().apply {
            put("collections", JSONArray(collectionRepository.pubKeyCollections.toJSON()))
            put("prefs", prefsUtil.export())
            if (dojoUtility.isDojoEnabled() && dojoUtility.exportDojoPayload() != null) {
                put("dojo", JSONObject(dojoUtility.exportDojoPayload()!!))
            }
        }
    }

    fun makeSupportBackup(): JSONObject {
        val payload = makePayload()
        val pubkeyInfoArray = payload.getJSONArray("collections")


        for (i in 0 until pubkeyInfoArray.length()) {
            (pubkeyInfoArray[i] as JSONObject).remove("lastRefreshed")
        }
        val meta = JSONObject()
        meta.put(
            "version_name",
            BuildConfig.VERSION_NAME
        )
        meta.put(
            "android_release",
            if (Build.VERSION.RELEASE == null) "" else Build.VERSION.RELEASE
        )
        meta.put("device_manufacturer", if (Build.MANUFACTURER == null) "" else Build.MANUFACTURER)
        meta.put("device_model", if (Build.MODEL == null) "" else Build.MODEL)
        meta.put("device_product", if (Build.PRODUCT == null) "" else Build.PRODUCT)

        payload.put("meta", meta)

        return payload
    }

    fun addVersionInfo(content: String): JSONObject {
        val payload = JSONObject()
        payload.put("version", BuildConfig.VERSION_CODE)
        payload.put("time", System.currentTimeMillis())
        payload.put("payload", content)
        return payload
    }

    fun decryptAndParseSamouraiPayload(backUp: String, password: String): PubKeyCollection? {
        try {
            val backUpJson = JSONObject(backUp)
            val decrypted = AESUtil.decrypt(
                backUpJson.getString("payload"),
                CharSequenceX(password),
                AESUtil.DefaultPBKDF2Iterations
            )
            val pubKeyCollection = PubKeyCollection(collectionLabel = "My Samourai wallet")
            val json = JSONObject(decrypted)
            if (json.has("wallet")) {
                val wallet = json.getJSONObject("wallet")
                val fingerprint = wallet.getString("fingerprint")
                val accounts =
                    if (wallet.has("accounts")) wallet.getJSONArray("accounts") else JSONArray()
                val biP49Accounts =
                    if (wallet.has("bip49_accounts")) wallet.getJSONArray("bip49_accounts") else JSONArray()
                val bip84Accounts =
                    if (wallet.has("bip84_accounts")) wallet.getJSONArray("bip84_accounts") else JSONArray()
                val whirlpoolAccount =
                    if (wallet.has("whirlpool_account")) wallet.getJSONArray("whirlpool_account") else JSONArray()

                //Add default BIP44 xpub account
                repeat(accounts.length()) {
                    val jsonObject = accounts.getJSONObject(it)
                    if (jsonObject.has("xpub")) {
                        val xpub = jsonObject.getString("xpub")
                        if (FormatsUtil.isValidXpub(xpub)) {
                            val pubKeyModel = PubKeyModel(
                                pubKey = xpub,
                                label = "Deposit BIP44 PUB",
                                AddressTypes.BIP44,
                                change_index = if (jsonObject.has("changeIdx")) jsonObject.getInt("changeIdx") else 0,
                                account_index = if (jsonObject.has("receiveIdx")) jsonObject.getInt(
                                    "receiveIdx"
                                ) else 0,
                                fingerPrint = fingerprint
                            )
                            pubKeyCollection.pubs.add(pubKeyModel)
                        }
                    }
                }

                //Add BIP49 xpubs
                repeat(biP49Accounts.length()) {
                    val jsonObject = biP49Accounts.getJSONObject(it)
                    if (jsonObject.has("ypub")) {
                        val xpub = jsonObject.getString("ypub")
                        if (FormatsUtil.isValidXpub(xpub)) {
                            val pubKeyModel = PubKeyModel(
                                pubKey = xpub,
                                label = "Deposit BIP49 PUB",
                                AddressTypes.BIP49,
                                change_index = if (jsonObject.has("changeIdx")) jsonObject.getInt("changeIdx") else 0,
                                account_index = if (jsonObject.has("receiveIdx")) jsonObject.getInt(
                                    "receiveIdx"
                                ) else 0,
                                fingerPrint = fingerprint
                            )
                            pubKeyCollection.pubs.add(pubKeyModel)
                        }
                    }
                }

                //Add BIP84 xpub
                repeat(bip84Accounts.length()) {
                    val jsonObject = bip84Accounts.getJSONObject(it)
                    if (jsonObject.has("zpub")) {
                        val xpub = jsonObject.getString("zpub")
                        if (FormatsUtil.isValidXpub(xpub)) {
                            val label = "Deposit BIP84 PUB"
                            val pubKeyModel = PubKeyModel(
                                pubKey = xpub,
                                label = label,
                                AddressTypes.BIP84,
                                change_index = if (jsonObject.has("changeIdx")) jsonObject.getInt("changeIdx") else 0,
                                account_index = if (jsonObject.has("receiveIdx")) jsonObject.getInt(
                                    "receiveIdx"
                                ) else 0,
                                fingerPrint = fingerprint
                            )
                            pubKeyCollection.pubs.add(pubKeyModel)
                        }
                    }
                }

                //Add Whirlpool accounts
                repeat(whirlpoolAccount.length()) {
                    val jsonObject = whirlpoolAccount.getJSONObject(it)
                    if (jsonObject.has("zpub")) {
                        val xpub = jsonObject.getString("zpub")
                        var label = "Whirlpool $it"
                        when (it) {
                            0 -> {
                                label = "Premix PUB"
                            }

                            1 -> {
                                label = "Postmix PUB"
                            }

                            2 -> {
                                label = "Bad Bank PUB"
                            }
                        }
                        if (FormatsUtil.isValidXpub(xpub)) {
                            val pubKeyModel = PubKeyModel(
                                pubKey = xpub,
                                label = label,
                                AddressTypes.BIP84,
                                change_index = if (jsonObject.has("changeIdx")) jsonObject.getInt("changeIdx") else 0,
                                account_index = if (jsonObject.has("receiveIdx")) jsonObject.getInt(
                                    "receiveIdx"
                                ) else 0,
                                fingerPrint = fingerprint
                            )
                            pubKeyCollection.pubs.add(pubKeyModel)
                        }
                    }
                }
                return pubKeyCollection
            } else {
                throw Exception("Invalid payload")
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun decryptSentinel(backUp: String, password: String): Triple<ArrayList<PubKeyCollection>?, JSONObject, JSONObject?> {
        val json = JSONObject(backUp)
        if (json.has("payload") && json.has("version")) {
            val payloadVersion = json.getInt("version");
            var decrypted = ""
            decrypted = if(payloadVersion == 1){
                AESUtil.decrypt(json.getString("payload"), CharSequenceX(password), AESUtil.DefaultPBKDF2Iterations)
            }else{
                AESUtil.decryptSHA256(json.getString("payload"), CharSequenceX(password), AESUtil.DefaultPBKDF2HMACSHA256Iterations)
            }
            val payloadJSON = JSONObject(decrypted)
            val collectionArrayType = object : TypeToken<ArrayList<PubKeyCollection?>?>() {}.type
            val collections = fromJSON<ArrayList<PubKeyCollection>>(payloadJSON.getJSONArray("collections").toString(), collectionArrayType)
            val prefs = payloadJSON.getJSONObject("prefs")
            var dojo: JSONObject? = null
            if (payloadJSON.has("dojo")) {
                dojo = payloadJSON.getJSONObject("dojo")
            }
            return Triple(collections, prefs, dojo)
        } else {
            throw  Exception("Invalid payload")
        }
    }


    fun decryptSentinelLegacy(backUp: String, password: String): Pair<ArrayList<PubKeyModel>, JSONObject?> {
        val jsonBackUp = JSONObject(backUp)
        if (jsonBackUp.has("payload") && jsonBackUp.has("version")) {
            val payloadVersion = jsonBackUp.getInt("version");
            val publicKeys: ArrayList<PubKeyModel> = arrayListOf()
            val decrypted: String = if(payloadVersion == 1){
                AESUtil.decrypt(jsonBackUp.getString("payload"), CharSequenceX(password), AESUtil.DefaultPBKDF2Iterations)
            }else{       
                AESUtil.decryptSHA256(jsonBackUp.getString("payload"), CharSequenceX(password), AESUtil.DefaultPBKDF2Iterations)
            }
            val payloadJSON = JSONObject(decrypted)

            if (payloadJSON.has("xpubs")) {
                val xpubs = payloadJSON.getJSONArray("xpubs")
                repeat(xpubs.length()) {
                    val xpubObject = xpubs.getJSONObject(it)
                    xpubObject.keys().forEach { key ->
                        val type = validate(key);
                        if (type == AddressTypes.BIP44) {
                            val pubKeyModel = PubKeyModel(
                                pubKey = key,
                                label = xpubObject.getString(key), type = AddressTypes.BIP44)
                            publicKeys.add(pubKeyModel)
                        }
                    }
                }
            }

            if (payloadJSON.has("bip49")) {
                val xpubs = payloadJSON.getJSONArray("bip49")
                repeat(xpubs.length()) {
                    val xpubObject = xpubs.getJSONObject(it)
                    xpubObject.keys().forEach { key ->
                        val type = validate(key);
                        if (type == AddressTypes.BIP49) {
                            val pubKeyModel = PubKeyModel(
                                pubKey = key,
                                label = xpubObject.getString(key), type = AddressTypes.BIP49)
                            publicKeys.add(pubKeyModel)
                        }
                    }
                }
            }

            if (payloadJSON.has("bip84")) {
                val xpubs = payloadJSON.getJSONArray("bip84")
                repeat(xpubs.length()) {
                    val xpubObject = xpubs.getJSONObject(it)
                    xpubObject.keys().forEach { key ->
                        val type = validate(key);
                        if (type == AddressTypes.BIP84) {
                            val pubKeyModel = PubKeyModel(
                                pubKey = key,
                                label = xpubObject.getString(key), type = AddressTypes.BIP84)
                            publicKeys.add(pubKeyModel)
                        }
                    }
                }
            }

            if (payloadJSON.has("legacy")) {
                val xpubs = payloadJSON.getJSONArray("legacy")
                repeat(xpubs.length()) {
                    val xpubObject = xpubs.getJSONObject(it)
                    xpubObject.keys().forEach { key ->
                        val type = validate(key);
                        if (type == AddressTypes.ADDRESS) {
                            val pubKeyModel = PubKeyModel(
                                pubKey = key,
                                label = xpubObject.getString(key), type = AddressTypes.ADDRESS)
                            publicKeys.add(pubKeyModel)
                        }
                    }
                }
            }
            var dojo: JSONObject? = null
            if (payloadJSON.has("dojo")) {
                if (dojoUtil.validate(payloadJSON.getString("dojo"))) {
                    dojo = payloadJSON.getJSONObject("dojo")
                }
            }
            return Pair(publicKeys,dojo)
        } else {
            throw  Exception("Invalid payload")
        }
    }


    suspend fun startImportCollections(pubKeyCollection: ArrayList<PubKeyCollection>, replace: Boolean) = withContext(Dispatchers.IO) {
        try {
            if (replace) {
                utxoDao.delete()
                txDao.delete()
                collectionRepository.reset()
            }
            pubKeyCollection.forEach { collectionRepository.addNew(it) }
        } catch (ex: Exception) {
            throw  CancellationException(ex.message)
        }
    }

    suspend fun importDojo(dojo: JSONObject) = withContext(Dispatchers.IO) {
        dojoUtility.setDojo(dojo.toString())
    }

    fun importPrefs(it: JSONObject) {
        prefsUtil.import(it)
    }


    private fun validate(code: String): AddressTypes? {

        var payload = code

        if (code.startsWith("BITCOIN:")) {
            payload = code.substring(8)

        }
        if (code.startsWith("bitcoin:")) {
            payload = code.substring(8)
        }
        if (code.startsWith("bitcointestnet:")) {
            payload = code.substring(15)
        }
        if (code.contains("?")) {
            payload = code.substring(0, code.indexOf("?"))
        }
        if (code.contains("?")) {
            payload = code.substring(0, code.indexOf("?"))
        }

        var type = AddressTypes.ADDRESS

        if (code.startsWith("xpub") || code.startsWith("tpub")) {
            type = AddressTypes.BIP44
        } else if (code.startsWith("ypub") || code.startsWith("upub")) {
            type = AddressTypes.BIP49
        } else if (code.startsWith("zpub") || code.startsWith("vpub")) {
            type = AddressTypes.BIP84
        }

        return if (type == AddressTypes.ADDRESS) {
            FormatsUtil.isValidBitcoinAddress(code)
            null
        } else {
            if (FormatsUtil.isValidXpub(code)) {
                type
            } else {
                null
            }
        }
    }
}