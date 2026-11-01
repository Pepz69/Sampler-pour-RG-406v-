package com.rg406v.sampler

import android.content.Context
import android.net.Uri
import com.rg406v.sampler.audio.PadParams
import com.rg406v.sampler.audio.Sample
import com.rg406v.sampler.audio.SampleLoader
import com.rg406v.sampler.audio.WavIO
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Kits (16 pads : samples + réglages) et séquences nommés, stockés dans le dossier privé de l'appli. */
class Storage(private val ctx: Context) {
    private val kitsDir = File(ctx.filesDir, "kits")
    private val seqDir = File(ctx.filesDir, "sequences")
    val projectsRoot = File(ctx.filesDir, "projects")

    private fun safe(name: String): String {
        val s = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_")
        return if (s.isEmpty()) "sans nom" else s.take(40)
    }

    fun listKits(): List<String> =
        (kitsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith("_") }?.map { it.name } ?: emptyList())
            .sorted()

    fun hasKit(name: String) = File(File(kitsDir, safe(name)), "kit.json").exists()

    /** Enregistre les 16 pads. `cache` évite de réécrire les samples inchangés (sauvegarde auto rapide). */
    fun saveKit(name: String, pads: List<PadParams>, cache: Array<Sample?>? = null, root: File = kitsDir) {
        val dir = File(root, safe(name))
        dir.mkdirs()
        val arr = JSONArray()
        for (i in pads.indices) {
            val p = pads[i]
            val o = p.toJson()
            val s = p.sample
            val f = File(dir, "pad_%02d.wav".format(i + 1))
            if (s != null) {
                if (cache == null || cache[i] !== s || !f.exists()) {
                    WavIO.write(f, s.data, s.sampleRate)
                    if (cache != null) cache[i] = s
                }
                o.put("wav", true)
                o.put("sname", s.name)
            } else {
                f.delete()
                if (cache != null) cache[i] = null
                o.put("wav", false)
            }
            arr.put(o)
        }
        File(dir, "kit.json").writeText(arr.toString())
    }

    fun loadKit(name: String, pads: List<PadParams>, root: File = kitsDir): Boolean {
        val dir = File(root, safe(name))
        val jf = File(dir, "kit.json")
        if (!jf.exists()) return false
        return try {
            val arr = JSONArray(jf.readText())
            for (i in 0 until minOf(pads.size, arr.length())) {
                val o: JSONObject = arr.getJSONObject(i)
                val p = pads[i]
                p.applyJson(o)
                if (o.optBoolean("wav", false)) {
                    val loaded = SampleLoader.load(ctx, Uri.fromFile(File(dir, "pad_%02d.wav".format(i + 1))))
                    if (loaded != null) {
                        val nm = o.optString("sname", loaded.name)
                        p.sample = Sample(nm, loaded.data, loaded.sampleRate)
                        p.sampleName = nm
                    }
                } else {
                    p.sample = null
                }
                p.clearHistory()
                p.sampleVersion++
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteKit(name: String) {
        File(kitsDir, safe(name)).deleteRecursively()
    }

    fun listSeq(): List<String> =
        (seqDir.listFiles()?.filter { it.isFile && it.extension == "json" }
            ?.map { it.nameWithoutExtension } ?: emptyList()).sorted()

    fun saveSeq(name: String, json: String) {
        seqDir.mkdirs()
        File(seqDir, safe(name) + ".json").writeText(json)
    }

    fun loadSeq(name: String): String? {
        val f = File(seqDir, safe(name) + ".json")
        return if (f.exists()) f.readText() else null
    }

    fun deleteSeq(name: String) {
        File(seqDir, safe(name) + ".json").delete()
    }

    // ---- projets : kit + état complet (effets, automations, séquences, réglages)
    fun listProjects(): List<String> =
        (projectsRoot.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()).sorted()

    fun hasProject(name: String) = File(File(projectsRoot, safe(name)), "project.json").exists()

    fun writeProjectState(name: String, json: String) {
        val dir = File(projectsRoot, safe(name))
        dir.mkdirs()
        File(dir, "project.json").writeText(json)
    }

    fun readProjectState(name: String): String? {
        val f = File(File(projectsRoot, safe(name)), "project.json")
        return if (f.exists()) f.readText() else null
    }

    fun deleteProject(name: String) {
        File(projectsRoot, safe(name)).deleteRecursively()
    }

    fun writeState(json: String) {
        File(ctx.filesDir, "state.json").writeText(json)
    }

    fun readState(): String? {
        val f = File(ctx.filesDir, "state.json")
        return if (f.exists()) f.readText() else null
    }
}
