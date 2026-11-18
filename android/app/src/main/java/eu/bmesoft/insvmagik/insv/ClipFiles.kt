package eu.bmesoft.insvmagik.insv

/**
 * Which picked files belong together. Insta360 names (from the cameras' manuals):
 * - `VID_<date>_<time>_00_<seq>.insv` + `VID_…_10_<seq>.insv`: one clip in two files, one per lens
 *   (ONE X2, X3 at 5.7K). `_01_` / `_11_` are the low-res `.lrv` proxies of the same.
 * - `VID_<date>_<time>_<seq>.insv`: one file with both lenses as two video tracks (X4 and newer,
 *   Antigravity A1).
 */
object ClipFiles {
    /**
     * What a download may add after the real name: a copy number and an extension guessed from
     * the MIME type (a phone browser saved `VID_…_10_008.insv` as `VID_…_10_008.insv (1).3gpp`).
     */
    private const val TAIL = """(?: \(\d+\))?(?:\.[a-z0-9]{1,5})?$"""
    private val TWO = Regex("""^(VID|LRV)_(\d{8}_\d{6})_(\d)(\d)_(\d{3})\.(insv|lrv)$TAIL""", RegexOption.IGNORE_CASE)
    private val ONE = Regex("""^(VID|LRV)_(\d{8}_\d{6})_(\d{3})\.(insv|lrv)$TAIL""", RegexOption.IGNORE_CASE)

    data class Name(val clip: String, val lens: Int?, val proxy: Boolean)

    /** Clip key and lens (0 or 1; null when the file holds both). Null for a name that doesn't fit. */
    fun parse(fileName: String): Name? {
        TWO.matchEntire(fileName)?.let { m ->
            val (prefix, time, lens, kind, seq, ext) = m.destructured
            val proxy = kind == "1" || prefix.equals("LRV", true) || ext.equals("lrv", true)
            return Name("${time}_$seq", lens.toInt(), proxy)
        }
        ONE.matchEntire(fileName)?.let { m ->
            val (prefix, time, seq, ext) = m.destructured
            return Name("${time}_$seq", null, proxy = prefix.equals("LRV", true) || ext.equals("lrv", true))
        }
        return null
    }

    /** Notes about the picked set, empty when it's one complete clip. */
    fun check(fileNames: List<String>): List<String> {
        val notes = mutableListOf<String>()
        val parsed = fileNames.map { it to parse(it) }
        parsed.filter { it.second == null }.forEach { notes += "${it.first}: not an Insta360 file name; read anyway." }
        val names = parsed.mapNotNull { it.second }.filter { !it.proxy }
        if (parsed.any { it.second?.proxy == true }) notes += "Low-res proxy (.lrv) picked: fine for testing, but the real export uses the .insv."
        val clips = names.groupBy { it.clip }
        if (clips.size > 1) notes += "Files from ${clips.size} different clips: pick one clip at a time."
        for ((clip, files) in clips) {
            val lenses = files.mapNotNull { it.lens }.toSet()
            if (files.any { it.lens == null }) continue
            if (0 in lenses && 1 !in lenses) notes += "$clip: only lens 0 (_00_) picked; pick its _10_ file too."
            if (1 in lenses && 0 !in lenses) notes += "$clip: only lens 1 (_10_) picked; pick its _00_ file too."
        }
        return notes
    }
}
