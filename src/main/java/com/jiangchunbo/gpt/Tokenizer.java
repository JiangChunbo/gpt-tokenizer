package com.jiangchunbo.gpt;

import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Tokenizer {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(Tokenizer.class);

    public static void main(String[] args) {
        List<String> result = Tokenizer.encode("啦啦啦");
        System.out.println(result.size());
    }

    /**
     * 模式匹配常量，用于匹配传入文字的分词?
     * 's|'t|'re|'ve|'m|'ll|'d| ?           英文
     * \p{L}+| ?                            字母类别的码点
     * \p{N}+| ?                            任何类型的数字字符
     * [^\s\p{L}\p{N}]+|\s+(?!\S)|\s+
     * [^\s\p{L}\p{N}]+                非空白、非字母、非数字
     * \s+(?!\S)                       非空白字符向前，匹配多个非空白
     * \s+                             多个空白
     */
    private final static Pattern pat = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+", Pattern.UNICODE_CHARACTER_CLASS);


    public static JSONObject encoder;


    public final static Map<String, String> cache = new HashMap<>();

    /**
     * bpe 的排序
     */
    public static Map<Pair<String>, Integer> bpe_ranks;


    static {
        try (InputStream inputStream = Tokenizer.class.getResourceAsStream("encoder.json")) {
            if (inputStream != null) {
                Scanner scanner = new Scanner(inputStream, "UTF-8").useDelimiter("\\A");
                String fileContent = scanner.hasNext() ? scanner.next() : "";
                encoder = JSONObject.parseObject(fileContent);
            } else {
                encoder = new JSONObject();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String bpe_file = "";
        try (InputStream inputStream = Tokenizer.class.getResourceAsStream("vocab.bpe")) {
            if (inputStream != null) {
                Scanner scanner = new Scanner(inputStream, "UTF-8").useDelimiter("\\A");
                bpe_file = scanner.hasNext() ? scanner.next() : "";
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        // lines: bpe 文件由 \n 分隔的字符串
        String[] lines = bpe_file.split("\n");
        List<Pair<String>> bpe_merges = new ArrayList<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i];
            List<String> pair = Arrays.stream(line.split("(\\s+)")).filter(e -> e.trim().length() > 0).collect(Collectors.toList());
            bpe_merges.add(new Pair<>(pair.get(0), pair.get(1)));
        }

        List<Integer> rankList = new ArrayList<>();
        for (int i = 0; i < bpe_merges.size(); i++) {
            rankList.add(i);
        }
        bpe_ranks = dictZip(bpe_merges, rankList);
    }

    /**
     * 编码的入口
     *
     * @param text 需要编码的字符串
     * @return 编码结果
     */
    public static List<String> encode(String text) {
        ArrayList<String> bpeTokens = new ArrayList<>();
        Matcher matcher = pat.matcher(text);
        ArrayList<String> matches = new ArrayList<>();
        while (matcher.find()) {
            String group = matcher.group(0);
            matches.add(group);
        }

        for (String token : matches) {
            token = encodeStr(token).stream().map(x -> {
                return String.valueOf((char) (int) byte_encoder.get(x));
            }).collect(Collectors.joining(""));
            List<String> new_tokens = Arrays.stream(bpe(token).split(" ")).map(x -> String.valueOf(encoder.get(x))).collect(Collectors.toList());
            bpeTokens.addAll(new_tokens);
        }
        return bpeTokens;
    }

    /**
     * 将每个字节转换成整数值
     *
     * @param str
     * @return
     */
    public static List<Integer> encodeStr(String str) {
        ArrayList<Integer> result = new ArrayList<>();
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        for (byte aByte : bytes) {
            // 获得字节之后可能是负数，需要转为无符号
            result.add(Byte.toUnsignedInt(aByte));
        }
        return result;
    }

    public final static Map<Integer, Integer> byte_encoder = bytes_to_unicode();

    public static Map<Integer, Integer> bytes_to_unicode() {
        ArrayList<Integer> bs = new ArrayList<>();
        for (char i = '!'; i < '~' + 1; i++) {
            bs.add((int) i);
        }
        for (char i = '¡'; i < '¬' + 1; i++) {
            bs.add((int) i);
        }
        for (char i = '®'; i < 'ÿ' + 1; i++) {
            bs.add((int) i);
        }
        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n = n + 1;
            }
        }

        cs = cs.stream().map(x -> x).collect(Collectors.toList());
        HashMap<Integer, Integer> result = new HashMap<>();
        for (int i = 0; i < bs.size(); i++) {
            result.put(bs.get(i), cs.get(i));
        }
        return result;
    }

    public static String bpe(String token) {
        if (cache.get(token) != null) {
            return cache.get(token);
        }

        List<String> word = Arrays.stream(token.split("")).collect(Collectors.toList());

        Set<Pair<String>> pairs = get_pairs(word);
        if (pairs.size() == 0) {
            return token;
        }

        while (true) {
            final int[] min = {Integer.MAX_VALUE};
            HashMap<Integer, Pair<String>> minPairs = new HashMap<>();
            pairs.forEach(pair -> {
                Integer rank = bpe_ranks.get(pair);
                minPairs.put(rank == null ? Integer.MAX_VALUE : rank, pair);
                if (rank != null) {
                    min[0] = Math.min(min[0], rank);
                }
            });

            Pair<String> bigram = minPairs.get(min[0]);

            if (!bpe_ranks.containsKey(bigram)) {
                break;
            }

            String first = bigram.prevChar;
            String second = bigram.thisChar;

            List<String> new_word = new ArrayList<>();
            int i = 0;
            while (i < word.size()) {
                int j = -1;
                for (int findIndex = i; findIndex < word.size(); findIndex++) {
                    if (word.get(findIndex).equals(first)) {
                        j = findIndex;
                        break;
                    }
                }
                if (j == -1) {
                    new_word.addAll(word.subList(i, word.size()));
                    break;
                }
                new_word.addAll(word.subList(i, j));
                i = j;
                if (word.get(i).equals(first) && i < word.size() - 1 && word.get(i + 1).equals(second)) {
                    new_word.add(first + second);
                    i = i + 2;
                } else {
                    new_word.add(word.get(i));
                    i = i + 1;
                }
            }

            word = new_word;
            if (word.size() == 1) {
                break;
            } else {
                pairs = get_pairs(word);
            }
        }
        word = Arrays.asList(String.join(" ", word).split(""));
        cache.put(token, String.join("", word));
        return String.join("", word);
    }

    public static Set<Pair<String>> get_pairs(List<String> word) {
        Set<Pair<String>> pairs = new HashSet<>();
        String prev_char = word.get(0);
        for (int i = 1; i < word.size(); i++) {
            String c = word.get(i);
            pairs.add(new Pair<>(prev_char, c));
            prev_char = c;
        }
        return pairs;
    }

    public static class Pair<T> {
        private final T prevChar;
        private final T thisChar;

        public Pair(T prevChar, T thisChar) {
            this.prevChar = prevChar;
            this.thisChar = thisChar;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Pair<?> pair = (Pair<?>) o;
            return Objects.equals(prevChar, pair.prevChar) && Objects.equals(thisChar, pair.thisChar);
        }

        @Override
        public int hashCode() {
            return Objects.hash(prevChar, thisChar);
        }
    }


    public static Map<Pair<String>, Integer> dictZip(List<Pair<String>> x, List<Integer> y) {
        HashMap<Pair<String>, Integer> result = new HashMap<>();
        for (int i = 0; i < x.size(); i++) {
            result.put(x.get(i), y.get(i));
        }
        return result;
    }
}