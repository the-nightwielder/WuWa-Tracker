package com.nightwielder.wuwatracker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale

private val Context.dataStore by preferencesDataStore("wuwa_tracker")

object WeaponMapping {
    /**
     * Canonical baseline mapping of all 40 limited 5-star resonators to their signature weapons.
     * Current as of Version 3.8.
     */
    val BASELINE_SIGNATURE_WEAPONS: Map<String, String> = mapOf(
        "Aemeath" to "Everbright Polestar",
        "Augusta" to "Thunderflare Dominion",
        "Brant" to "Unflickering Valor",
        "Camellya" to "Red Spring",
        "Cantarella" to "Whispers of Sirens",
        "Carlotta" to "The Last Dance",
        "Cartethyia" to "Defier's Thorn",
        "Changli" to "Blazing Brilliance",
        "Chisa" to "Kumokiri",
        "Ciaccona" to "Woodland Aria",
        "Denia" to "Forged Dwarf Star",
        "Galbrena" to "Lux & Umbra",
        "Hiyuki" to "Frostburn",
        "Hsin" to "Blooming Jadehaven",
        "Iuno" to "Moongazer's Sigil",
        "Jingran" to "Thousandfold Deliverance",
        "Jinhsi" to "Ages of Harvest",
        "Jiyan" to "Verdant Summit",
        "Lily" to "Lullaby",
        "Lucilla" to "Freeze Frame",
        "Lucy" to "Spectral Trigger",
        "Lupa" to "Wildfire Mark",
        "Luuk Herssen" to "Daybreaker's Spine",
        "Lynae" to "Spectrum Blaster",
        "Mornye" to "Starfield Calibrator",
        "Phoebe" to "Luminous Hymn",
        "Phrolova" to "Lethean Elegy",
        "Qingxiao" to "Glint of Clouds",
        "Qiuyuan" to "Emerald Sentence",
        "Rebecca" to "Skull Thrasher",
        "Roccia" to "Tragicomedy",
        "Shorekeeper" to "Stellar Symphony",
        "Sigrika" to "Solsworn Ciphers",
        "Suisui" to "Firstlight's Herald",
        "Suoming" to "Unspoken Rue",
        "Xiangli Yao" to "Verity's Handle",
        "Yangyang: Xuanling" to "Azure Oath",
        "Yangyang Xuanling" to "Azure Oath",
        "Yinlin" to "Stringmaster",
        "Zani" to "Blazing Justice",
        "Zhezhi" to "Rime-Draped Sprouts"
    )

    private fun normalize(name: String): String =
        name.replace(Regex("[:()\\-_]"), " ").replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)

    /**
     * Resolves signature weapon with fallback:
     * 1. Dynamic learned map (new future resonators saved in DataStore)
     * 2. Baseline verified 40 resonators
     * 3. Normalized / partial matching
     */
    fun resolveSignatureWeapon(name: String, dynamicMap: Map<String, String> = emptyMap()): String? {
        val norm = normalize(name)
        dynamicMap.entries.firstOrNull { normalize(it.key) == norm }?.value?.let { return it }
        BASELINE_SIGNATURE_WEAPONS.entries.firstOrNull { normalize(it.key) == norm }?.value?.let { return it }
        BASELINE_SIGNATURE_WEAPONS.entries.firstOrNull {
            val keyNorm = normalize(it.key)
            norm.contains(keyNorm) || keyNorm.contains(norm)
        }?.value?.let { return it }
        return null
    }

    private val DYNAMIC_KEY = stringPreferencesKey("dynamic_weapon_mappings")

    suspend fun loadDynamicMappings(context: Context): Map<String, String> {
        val raw = context.dataStore.data.first()[DYNAMIC_KEY] ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k)
            }
            map
        }.getOrDefault(emptyMap())
    }

    suspend fun learnNewMapping(context: Context, resonator: String, weapon: String) {
        if (resonator.isBlank() || weapon.isBlank()) return
        val current = loadDynamicMappings(context).toMutableMap()
        if (resolveSignatureWeapon(resonator, current) == null) {
            current[resonator.trim()] = weapon.trim()
            context.dataStore.edit { p ->
                val obj = JSONObject()
                current.forEach { (k, v) -> obj.put(k, v) }
                p[DYNAMIC_KEY] = obj.toString()
            }
        }
    }
}
