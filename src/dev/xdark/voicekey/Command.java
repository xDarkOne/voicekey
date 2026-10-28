package dev.xdark.voicekey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A spoken command, said freely: "открой Лампу", "открой трейлер паука на ютубе",
 * "найди в кинопоиске Дюна", "найди в вк видео обзор", or just "интерстеллар".
 *
 * A leading verb is dropped; an app named anywhere ("на ютубе", "в Лампе", "в вк видео") is
 * where to search; the rest is the query. An app named with nothing else is opened. With no app
 * named, the query goes to the default app. The recognizer garbles app names ("на ютуби"), so
 * they are matched loosely.
 */
final class Command {
    enum Action { OPEN, SEARCH, UNKNOWN }
    enum Target { YOUTUBE, LAMPA, KINOPOISK, VK, RUTUBE }

    final Action action;
    final Target target;
    final String query;
    /** The app wasn't named: the search goes to the default one. */
    final boolean guessed;

    private Command(Action action, Target target, String query, boolean guessed) {
        this.action = action;
        this.target = target;
        this.query = query;
        this.guessed = guessed;
    }

    private static final Set<String> VERBS = new HashSet<>(Arrays.asList(
            "открой", "открыть", "откройте", "запусти", "запустить", "включи", "включить", "поставь",
            "найди", "найти", "найдите", "поищи", "поискать", "ищи", "искать", "покажи", "показать",
            "хочу", "давай", "посмотреть", "посмотрим", "смотреть"));
    private static final Set<String> FILLERS = new HashSet<>(Arrays.asList("пожалуйста", "мне", "ка"));
    private static final Set<String> PREPOSITIONS = new HashSet<>(Arrays.asList("на", "в", "во"));

    /**
     * The best of the recognizer's alternatives: the likeliest that names an app, as names get
     * garbled ("на ютуби") more than the rest; else the likeliest of all.
     */
    static Command best(List<String> heard, Target fallback) {
        Command first = null;
        for (String phrase : heard) {
            Command c = parse(phrase, fallback);
            if (first == null) first = c;
            if (c.action != Action.UNKNOWN && !c.guessed) return c;
        }
        return first;
    }

    static Command parse(String phrase, Target fallback) {
        // The recognizer may punctuate: "Найди на ютубе: «Дюна»." Hyphens and dots inside words stay.
        String cleaned = phrase.replaceAll("[:;,!?«»\"“”]", " ").trim()
                .replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
        List<String> words = new ArrayList<>(Arrays.asList(cleaned.split("\\s+")));
        words.removeIf(String::isEmpty);
        List<String> low = new ArrayList<>();
        for (String w : words) low.add(normalize(w));
        joinSplitNames(words, low);

        // Leading verbs and fillers: "открой", "найди-ка мне", "хочу посмотреть".
        while (!low.isEmpty() && (VERBS.contains(low.get(0)) || FILLERS.contains(low.get(0)))) {
            words.remove(0);
            low.remove(0);
        }
        // "найди пожалуйста на ютубе …": fillers go from the middle too, from both lists alike.
        for (int i = low.size() - 1; i >= 0; i--) {
            if (FILLERS.contains(low.get(i))) {
                words.remove(i);
                low.remove(i);
            }
        }

        Target named = null;
        for (int i = 0; i < low.size(); i++) {
            Target t = appOf(low.get(i));
            if (t == null) continue;
            boolean afterPreposition = i > 0 && PREPOSITIONS.contains(low.get(i - 1));
            // An app right after the verb, or the whole phrase. Elsewhere only "на/в …", so
            // "Аладдин и волшебная лампа" stays a title.
            if (afterPreposition || i == 0) {
                named = t;
                int from = afterPreposition ? i - 1 : i;
                int to = i + 1;
                // "вк видео", "vk video": the second word is part of the name.
                if (t == Target.VK && to < low.size() && (low.get(to).equals("видео") || low.get(to).equals("video"))) to++;
                words.subList(from, to).clear();
                low.subList(from, to).clear();
                break;
            }
        }

        String query = String.join(" ", words).trim();
        if (named != null) {
            return query.isEmpty()
                    ? new Command(Action.OPEN, named, "", false)
                    : new Command(Action.SEARCH, named, query, false);
        }
        if (query.isEmpty()) return new Command(Action.UNKNOWN, null, cleaned, false);
        return new Command(Action.SEARCH, fallback, query, true);
    }

    /** Lower case, "е" for "ё", and no "-ка": "поищи-ка" is "поищи". */
    private static String normalize(String word) {
        return word.toLowerCase(Locale.forLanguageTag("ru")).replace('ё', 'е').replaceAll("-ка$", "");
    }

    /** "you tube", "ю туб", "кино поиск", "ру туб", "vk video" spoken apart → one word. */
    private static void joinSplitNames(List<String> words, List<String> low) {
        for (int i = 0; i + 1 < low.size(); i++) {
            String a = low.get(i), b = low.get(i + 1);
            boolean join = ((a.equals("you") || a.equals("ю")) && (b.startsWith("tube") || b.startsWith("туб")))
                    || (a.equals("кино") && b.startsWith("поиск"))
                    || ((a.equals("ру") || a.equals("ru")) && (b.startsWith("туб") || b.startsWith("tube")));
            if (join) {
                words.set(i, words.get(i) + words.get(i + 1));
                words.remove(i + 1);
                low.set(i, a + b);
                low.remove(i + 1);
            }
        }
    }

    static Target appOf(String word) {
        if (word.startsWith("youtub")) return Target.YOUTUBE;
        if (word.equals("lampa")) return Target.LAMPA;
        if (word.startsWith("rutub")) return Target.RUTUBE;
        if (word.startsWith("kinopoisk")) return Target.KINOPOISK;
        if (word.equals("vk") || word.equals("вк") || word.equals("вэка") || word.startsWith("вконтакт")
                || word.equals("контакте")) return Target.VK;
        String stem = word.replaceAll("[аеиоуыэюяь]+$", "");
        if (stem.length() >= 3 && distance(stem, "ютуб") <= 1) return Target.YOUTUBE;
        if (stem.equals("ютьюб") || stem.equals("ютюб") || stem.equals("ютубчик")) return Target.YOUTUBE;
        if (stem.length() >= 4 && distance(stem, "рутуб") <= 1) return Target.RUTUBE;
        if (stem.length() >= 7 && distance(stem, "кинопоиск") <= 2) return Target.KINOPOISK;
        if (stem.equals("ламп") || stem.equals("лампочк")) return Target.LAMPA;
        return null;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    @Override
    public String toString() {
        return action + " " + target + (guessed ? "?" : "") + " «" + query + "»";
    }
}
