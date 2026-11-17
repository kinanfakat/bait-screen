package app.noortv;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * مسابقة العيلة: one game at a time, shared by the TV screen (QuizActivity) and the phones (MessageServer).
 * Lobby → ten questions (20 seconds each, or until everyone answered) → a short reveal after each → the podium.
 * A right answer is 10 points plus up to 5 for speed.
 *
 * The questions: the ones below plus assets/quiz.txt (about 340 in all). صح ولا غلط (tf mode) is the same game with
 * statements from assets/tf.txt, 15 of them, 6 seconds each, where speed counts double. Questions already asked are
 * remembered on the TV and come back only once all the others have been asked.
 */
final class QuizGame {
    static final int TOTAL = 10, TF_TOTAL = 15;
    static final long QUESTION_MS = 20_000, TF_MS = 6_000;

    private static boolean tf;
    private static SharedPreferences prefs;
    private static List<String[]> quizBank, tfBank;

    /** {question, right answer, wrong, wrong, wrong}; the options are shuffled for each game. */
    private static final String[][] BANK = {
            {"كم عدد سور القرآن الكريم؟", "114", "110", "120", "104"},
            {"ما أطول سورة في القرآن الكريم؟", "البقرة", "آل عمران", "النساء", "الكهف"},
            {"ما أقصر سورة في القرآن الكريم؟", "الكوثر", "الإخلاص", "الفلق", "الناس"},
            {"كم عدد أركان الإسلام؟", "5", "6", "4", "7"},
            {"كم عدد أركان الإيمان؟", "6", "5", "7", "4"},
            {"في أي شهر نزل القرآن الكريم؟", "رمضان", "شعبان", "رجب", "محرم"},
            {"كم عدد الصلوات المفروضة في اليوم؟", "5", "3", "4", "6"},
            {"من أول من أسلم من الرجال؟", "أبو بكر الصديق", "عمر بن الخطاب", "عثمان بن عفان", "علي بن أبي طالب"},
            {"ما اسم أم النبي ﷺ؟", "آمنة بنت وهب", "حليمة السعدية", "خديجة بنت خويلد", "فاطمة بنت أسد"},
            {"في أي مدينة وُلد النبي ﷺ؟", "مكة المكرمة", "المدينة المنورة", "الطائف", "القدس"},
            {"إلى أي مدينة هاجر النبي ﷺ؟", "المدينة المنورة", "الطائف", "دمشق", "مصر"},
            {"كم عدد أجزاء القرآن الكريم؟", "30", "20", "40", "60"},
            {"ما السورة التي لا تبدأ بالبسملة؟", "التوبة", "الفاتحة", "الأنفال", "الملك"},
            {"ما أول سورة نزلت آياتها على النبي ﷺ؟", "العلق", "الفاتحة", "المدثر", "البقرة"},
            {"من النبي الذي التقمه الحوت؟", "يونس", "موسى", "نوح", "يوسف"},
            {"من النبي الذي رفع قواعد الكعبة مع ابنه إسماعيل؟", "إبراهيم", "آدم", "نوح", "محمد ﷺ"},
            {"ما الصلاة التي ليس فيها ركوع ولا سجود؟", "صلاة الجنازة", "صلاة العيد", "صلاة الاستسقاء", "صلاة الضحى"},
            {"كم ركعة صلاة الفجر (الفرض)؟", "2", "3", "4", "1"},
            {"كم ركعة صلاة المغرب؟", "3", "2", "4", "5"},
            {"ما السورة التي تعدل ثلث القرآن؟", "الإخلاص", "الفاتحة", "يس", "الملك"},
            {"ما أول مسجد بُني في الإسلام؟", "مسجد قباء", "المسجد النبوي", "المسجد الحرام", "المسجد الأقصى"},
            {"ما القبلة الأولى للمسلمين؟", "المسجد الأقصى", "الكعبة", "مسجد قباء", "المسجد النبوي"},
            {"من الملَك الموكَّل بالوحي؟", "جبريل", "ميكائيل", "إسرافيل", "مالك"},
            {"في أي شهر يكون الحج؟", "ذو الحجة", "رمضان", "شوال", "محرم"},
            {"يوم عرفة هو اليوم كم من ذي الحجة؟", "9", "10", "8", "12"},
            {"من النبي الملقَّب بكليم الله؟", "موسى", "عيسى", "إبراهيم", "داود"},
            {"من النبي الذي صنع السفينة؟", "نوح", "هود", "صالح", "لوط"},
            {"كم عدد آيات سورة الفاتحة؟", "7", "5", "6", "8"},
            {"من خاتم الأنبياء والمرسلين؟", "محمد ﷺ", "عيسى", "موسى", "إبراهيم"},
            {"من أول زوجات النبي ﷺ؟", "خديجة بنت خويلد", "عائشة بنت أبي بكر", "حفصة بنت عمر", "سودة بنت زمعة"},
            {"في أي مدينة يقع الجامع الأموي؟", "دمشق", "حلب", "القاهرة", "بغداد"},
            {"ما عاصمة سوريا؟", "دمشق", "حلب", "حمص", "اللاذقية"},
            {"ما عاصمة المملكة العربية السعودية؟", "الرياض", "جدة", "مكة المكرمة", "الدمام"},
            {"ما أطول نهر في العالم؟", "النيل", "الأمازون", "الفرات", "الدانوب"},
            {"ما أكبر محيط في العالم؟", "المحيط الهادئ", "المحيط الأطلسي", "المحيط الهندي", "المحيط المتجمد الشمالي"},
            {"كم عدد قارات العالم؟", "7", "5", "6", "8"},
            {"ما أكبر كوكب في المجموعة الشمسية؟", "المشتري", "زحل", "الأرض", "المريخ"},
            {"ما أقرب كوكب إلى الشمس؟", "عطارد", "الزهرة", "الأرض", "المريخ"},
            {"كم عدد أيام السنة الهجرية تقريباً؟", "354", "365", "300", "380"},
            {"ما الغاز الذي نتنفسه لنعيش؟", "الأكسجين", "ثاني أكسيد الكربون", "النيتروجين", "الهيليوم"},
            {"كم عدد ألوان قوس قزح؟", "7", "5", "6", "9"},
            {"ما أسرع حيوان بري؟", "الفهد", "الأسد", "الحصان", "الغزال"},
            {"كم عدد أرجل العنكبوت؟", "8", "6", "10", "4"},
            {"ما أكبر صحراء حارة في العالم؟", "الصحراء الكبرى", "صحراء الربع الخالي", "صحراء غوبي", "صحراء أتاكاما"},
            {"كم عدد الأشهر الهجرية؟", "12", "10", "11", "13"},
    };

    static final String LOBBY = "lobby", QUESTION = "question", REVEAL = "reveal", END = "end";

    private static String phase = END;   // no game until the TV opens one
    private static final Map<String, Integer> scores = new LinkedHashMap<>();
    private static final Map<String, Integer> answers = new LinkedHashMap<>();
    private static final List<String[]> questions = new ArrayList<>();   // {q, o0..o3, correct index}
    private static int index = -1;
    private static long questionAt;

    private QuizGame() {
    }

    /** A new game on the TV: tf true for صح ولا غلط. */
    private static Context app;
    private static boolean kids;
    private static int subject = -1;

    /** The quiz's questions: the kids' bank, or the grown-ups' in one subject (Deck.SUBJECTS) or all (-1). */
    static synchronized void choose(boolean forKids, int subjectIndex) {
        kids = forKids;
        subject = subjectIndex;
    }

    static synchronized String chosenName() {
        if (tf) return "";
        if (kids) return "🧒 للولاد";
        return subject >= 0 && subject < Deck.SUBJECTS.length ? Deck.SUBJECTS[subject][0] : "";
    }

    static synchronized void open(Context c, boolean trueFalse) {
        tf = trueFalse;
        app = c.getApplicationContext();
        recorded = false;
        prefs = c.getApplicationContext().getSharedPreferences("quiz", Context.MODE_PRIVATE);
        if (quizBank == null) {
            quizBank = new ArrayList<>();
            // all from assets/quiz.txt, each with its learning note (the old built-in list has none)
            for (String[] l : read(c, "quiz.txt")) if (l.length >= 5) quizBank.add(l);
        }
        if (tfBank == null) {
            tfBank = new ArrayList<>();
            for (String[] l : read(c, "tf.txt")) if (l.length >= 2) tfBank.add(l);
        }
        phase = LOBBY;
        onTv = true;
        scores.clear();
        answers.clear();
        questions.clear();
        index = -1;
    }

    /** The game screen running this game: an older screen left in the background stops instead of playing along. */
    private static Object owner;
    static synchronized void own(Object o) { owner = o; }
    static synchronized boolean owns(Object o) { return owner == o; }

    static synchronized void close() {
        onTv = false;
        phase = END;
        scores.clear();
        questions.clear();
        index = -1;
    }

    /** The quiz is on the TV (its screen is open), whatever its phase. */
    private static boolean onTv;

    static synchronized boolean join(String name) {
        // any time the quiz is on the TV: before it starts, in the middle, or after it ended (in for the next one)
        if (!onTv) return false;
        name = name.trim();
        if (name.isEmpty() || name.length() > 16) return false;
        if (!scores.containsKey(name)) scores.put(name, 0);
        return true;
    }

    private static List<String[]> read(Context c, String asset) {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\|");
                for (int i = 0; i < f.length; i++) f[i] = f[i].trim();
                out.add(f);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** n questions from the bank, the ones not asked yet first; the memory starts over once they have all been asked. */
    private static List<String[]> pick(List<String[]> bank, int n, String seenKey, Random r) {
        Set<String> seen = prefs == null ? new HashSet<>() : new HashSet<>(prefs.getStringSet(seenKey, new HashSet<>()));
        List<String[]> fresh = new ArrayList<>(), old = new ArrayList<>();
        for (String[] b : bank) (seen.contains(b[0]) ? old : fresh).add(b);
        Collections.shuffle(fresh, r);
        Collections.shuffle(old, r);
        if (fresh.size() < n) seen.clear();   // a new round of the whole bank
        List<String[]> out = new ArrayList<>(fresh.subList(0, Math.min(n, fresh.size())));
        for (int i = 0; out.size() < n && i < old.size(); i++) out.add(old.get(i));
        for (String[] b : out) seen.add(b[0]);
        if (prefs != null) prefs.edit().putStringSet(seenKey, seen).apply();
        return out;
    }

    static synchronized boolean hasPlayer(String name) { return scores.containsKey(name.trim()); }
    static synchronized boolean trueFalse() { return tf; }
    static synchronized long questionMs() { return tf ? TF_MS : QUESTION_MS; }
    static synchronized int options() { return tf ? 2 : 4; }

    /** Starts the questions; false when nobody joined. */
    static synchronized boolean start() {
        if (scores.isEmpty()) return false;
        Random r = new Random();
        questions.clear();
        if (tf) {
            for (String[] b : pick(tfBank, TF_TOTAL, "seenTf", r)) {
                questions.add(new String[]{b[0], "صح", "خطأ", "", "", "1".equals(b[1]) ? "0" : "1", b.length > 2 ? b[2] : ""});
            }
        } else {
            List<String[]> src = Deck.draw(app, kids ? "quiz_kids.txt" : "quiz.txt", 5, TOTAL,
                    kids ? null : Deck.subject(subject));
            for (String[] b : src) {
                List<String> opts = new ArrayList<>();
                for (int k = 1; k <= 4; k++) opts.add(b[k]);
                Collections.shuffle(opts, r);
                questions.add(new String[]{b[0], opts.get(0), opts.get(1), opts.get(2), opts.get(3),
                        String.valueOf(opts.indexOf(b[1])), b.length > 5 ? b[5] : ""});
            }
        }
        for (String k : scores.keySet()) scores.put(k, 0);
        index = -1;
        return next();
    }

    /** The next question, or the podium after the last; false when the game is over. */
    private static boolean recorded;

    static synchronized boolean next() {
        index++;
        answers.clear();
        if (index >= questions.size()) {
            // the game is over: its points go to the family league of the week, once
            if (!recorded) {
                recorded = true;
                League.add(app, scores);
            }
            phase = END + "_shown";
            return false;
        }
        phase = QUESTION;
        questionAt = System.currentTimeMillis();
        return true;
    }

    static synchronized void reveal() {
        if (!QUESTION.equals(phase)) return;
        phase = REVEAL;
    }

    static synchronized boolean answer(String name, int choice) {
        if (!QUESTION.equals(phase) || !scores.containsKey(name) || answers.containsKey(name)) return false;
        if (choice < 0 || choice >= options()) return false;
        answers.put(name, choice);
        if (choice == correct()) {
            long ms = questionMs();
            long left = Math.max(0, ms - (System.currentTimeMillis() - questionAt));
            scores.put(name, scores.get(name) + 10 + (int) ((tf ? 10 : 5) * left / ms));
        }
        return true;
    }

    static synchronized boolean everyoneAnswered() {
        return !scores.isEmpty() && answers.size() >= scores.size();
    }

    private static int correct() {
        return index >= 0 && index < questions.size() ? Integer.parseInt(questions.get(index)[5]) : -1;
    }

    static synchronized String phase() { return phase; }
    static synchronized int index() { return index; }
    static synchronized int total() { return questions.size(); }
    static synchronized String[] question() { return index >= 0 && index < questions.size() ? questions.get(index) : null; }
    static synchronized Map<String, Integer> scores() { return new LinkedHashMap<>(scores); }
    static synchronized Map<String, Integer> answers() { return new LinkedHashMap<>(answers); }
    static synchronized long questionAt() { return questionAt; }

    /** What a phone sees: the question and its options, the right answer only once revealed, and its score. */
    static synchronized String state(String name) {
        try {
            JSONObject o = new JSONObject();
            String ph = phase.startsWith(END) ? END : phase;
            o.put("phase", ph);
            o.put("players", scores.size());
            o.put("index", Math.max(0, index));
            o.put("total", questions.size() == 0 ? (tf ? TF_TOTAL : TOTAL) : questions.size());
            o.put("mode", tf ? "tf" : "quiz");
            o.put("score", name != null && scores.containsKey(name) ? scores.get(name) : 0);
            o.put("joined", name != null && scores.containsKey(name));
            String[] q = question();
            if (q != null && (QUESTION.equals(phase) || REVEAL.equals(phase))) {
                o.put("question", q[0]);
                o.put("options", tf ? new JSONArray(new String[]{q[1], q[2]}) : new JSONArray(new String[]{q[1], q[2], q[3], q[4]}));
                o.put("correctIndex", REVEAL.equals(phase) ? correct() : -1);
                if (REVEAL.equals(phase) && q.length > 6) o.put("note", q[6]);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"phase\":\"end\"}";
        }
    }

    static synchronized int correctIndex() { return correct(); }

    /** The learning note of the question on screen, or "". */
    static synchronized String note() {
        String[] q = question();
        return q != null && q.length > 6 ? q[6] : "";
    }
}
