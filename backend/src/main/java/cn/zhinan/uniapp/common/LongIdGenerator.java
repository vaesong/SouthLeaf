package cn.zhinan.uniapp.common;

import java.util.Random;

public class LongIdGenerator {

    private static final String BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Random RANDOM = new Random();

    /**
     * 生成带字母的长 ID
     *
     * @return 带字母的长 ID
     */
    public static String generateLongId() {
        long timestamp = System.currentTimeMillis(); // 当前时间戳（毫秒）
        int randomPart = RANDOM.nextInt(999999); // 生成 6 位随机数
        return toBase62(timestamp) + toBase62(randomPart); // 时间戳和随机数转换为 Base62 并拼接
    }

    /**
     * 将数字转换为 Base62 编码
     *
     * @param value 要编码的数字
     * @return Base62 字符串
     */
    private static String toBase62(long value) {
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(BASE62.charAt((int) (value % 62)));
            value /= 62;
        }
        return sb.reverse().toString(); // 反转字符串，因为低位在前
    }
}
