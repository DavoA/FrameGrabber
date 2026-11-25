package com.dave.framegrabber.telegram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The hashtag library, by category. The order of categories and of tags inside them is the order
 * of tags in a post. A tag belongs to one category. Also counts how often each tag was posted
 * (for «Частые»). Stored in the "telegram" preferences.
 */
class TagLibrary(ctx: Context) {

    class Category(var name: String, val tags: MutableList<String>)

    private val sp = ctx.getSharedPreferences("telegram", Context.MODE_PRIVATE)

    val categories: MutableList<Category> = load()

    /** All tags in post order. */
    val all: List<String> get() = categories.flatMap { it.tags }

    fun categoryOf(tag: String): Category? = categories.firstOrNull { c -> c.tags.any { it.equals(tag, true) } }

    fun contains(tag: String) = categoryOf(tag) != null

    /** Adds to [category] (created if missing). False if the tag is already somewhere. */
    fun add(tag: String, category: String): Boolean {
        if (contains(tag)) return false
        val c = categories.firstOrNull { it.name == category } ?: Category(category, mutableListOf()).also { categories += it }
        c.tags += tag
        return true
    }

    fun remove(tag: String) { categories.forEach { c -> c.tags.removeAll { it == tag } } }

    fun save() = write(categories)

    // ================================================================ usage («Частые»)

    fun uses(tag: String): Int = usage().optInt(tag, 0)

    fun countUse(tags: Collection<String>) {
        val u = usage()
        tags.forEach { u.put(it, u.optInt(it, 0) + 1) }
        sp.edit().putString("tagUse", u.toString()).apply()
    }

    /** Most posted tags that are still in the library. */
    fun frequent(n: Int): List<String> {
        val u = usage()
        return all.filter { u.optInt(it, 0) > 0 }.sortedByDescending { u.optInt(it, 0) }.take(n)
    }

    private fun usage() = runCatching { JSONObject(sp.getString("tagUse", "{}")!!) }.getOrDefault(JSONObject())

    // ================================================================ loading

    private fun load(): MutableList<Category> {
        sp.getString("tagCats", null)?.let { json ->
            runCatching {
                val a = JSONArray(json)
                return (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val t = o.getJSONArray("tags")
                    Category(o.getString("name"), (0 until t.length()).map { t.getString(it) }.toMutableList())
                }.toMutableList()
            }
        }
        // first start: the standard set, plus tags from the old flat list (before categories)
        val cats = defaults()
        val old = runCatching { JSONArray(sp.getString("tags", "[]")) }.getOrDefault(JSONArray())
        val mine = (0 until old.length()).map { old.getString(it) }
            .filter { t -> cats.none { c -> c.tags.any { it.equals(t, true) } } }
        if (mine.isNotEmpty()) cats += Category(MINE, mine.toMutableList())
        write(cats)
        return cats
    }

    private fun write(c: List<Category>) {
        val a = JSONArray()
        c.forEach { x -> a.put(JSONObject().put("name", x.name).put("tags", JSONArray().apply { x.tags.forEach { put(it) } })) }
        sp.edit().putString("tagCats", a.toString()).apply()
    }

    /** Adds the standard tags that are missing (keeps everything the user changed). Returns how many. */
    fun restoreDefaults(): Int {
        var n = 0
        defaults().forEach { d -> d.tags.forEach { if (add(it, d.name)) n++ } }
        save()
        return n
    }

    companion object {
        /** Where tags created while writing a post go. */
        const val MINE = "Мои"

        private val RAW = linkedMapOf(
            "Тело и внешность" to "натуральные силиконовые большие_сиськи маленькие_сиськи огромные_сиськи пышные стройные худые пышки толстые большие_жопы круглая_жопа плоская_жопа длинные_ноги короткие_ноги тату пирсинг бритые волосатые рыжие блондинки брюнетки шатенки азиатки латинки негритянки мулатки европеянки русские украинки белоруски",
            "Возраст / типаж" to "мамки молоденькие зрелые студентки секретарши учительницы врачи спортсменки фитнес йога домохозяйки жены подруги",
            "Позы и виды секса" to "наездница миссионерская догги сбоку стоя на_коленях от_первого_лица pov сиськотрах глубокий_минет глубокая_глотка горловой анал вагинальный лесбийский_секс жмж мжм групповуха оргия тройничок",
            "Действия и финиш" to "сперма_на_лицо сперма_в_рот сперма_на_сиськи сперма_на_жопу кремпай сквирт мокрый оргазм мастурбация дилдо страпон фистинг куни римминг массаж эромассаж масло",
            "Сюжеты и фетиши" to "измена инцест соседки подруги_жены лучшая_подруга босс_секретарша врач_пациентка публичный_секс на_улице в_машине в_офисе в_душе в_ванной на_кухне скрытая_камера любительское домашнее реал",
            "Специфические фетиши" to "молоко лактация беременные ноги футфетиш чулки колготки каблуки белье чулки_и_подвязки латекс кожа бдсм легкий_бдсм доминирование подчинение шлепки связанные кляп маска",
            "Качество и стиль" to "hd 4k профессиональное любительское голые полностью_голые с_одеждой раздевание медленное_раздевание крупный_план крупно без_цензуры",
            "Популярные комбинации" to "большая_жопа_анал мамки_измена блондинки_сквирт лесбиянки_страпон pov_глубокий_минет жмж_сквирт мжм_двойное_проникновение натуральные_большие_сиськи тату_анал молоко_мамки",
            "Дополнительно" to "азиатский_секс японский_секс корейский_секс индийский_секс арабский_секс черный_на_белой белый_на_чёрной межрасовый двойное_проникновение тройное_проникновение гангбанг буккаке фетиш_ног нюхать_ноги чулки_и_секс в_купальнике в_форме в_чулках в_колготках",
        )

        /** The standard set; a tag listed twice stays in its first category. */
        fun defaults(): MutableList<Category> {
            val seen = HashSet<String>()
            return RAW.map { (name, tags) ->
                Category(name, tags.split(' ').filter { it.isNotBlank() && seen.add(it.lowercase()) }.toMutableList())
            }.toMutableList()
        }
    }
}
