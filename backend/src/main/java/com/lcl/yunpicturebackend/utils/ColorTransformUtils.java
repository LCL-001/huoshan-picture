package com.lcl.yunpicturebackend.utils;

public class ColorTransformUtils {

    private ColorTransformUtils() {
        // 工具类不需要实例化
    }

    /**
     * 对该算法进行分析
     * e0020转换为e00200 还是 e00020：
     * 在COS中，会有前导0的情况，前面两位 和 中间两位 和 最后两位 互不相干，
     * <p>
     * 所以，我们要分开看：
     * 对于前导0，由于0在高位时对低位的数字毫无影响，所以可以省略，而在低位的0不能省略。
     * <p>
     * 分析：
     * e0020就是这样，第一位是e，e在前两位的高位，所以他是没有省略的，所以在完整的写法中前两位肯定是e0。
     * 那么对剩下的020进行分析：0在高位，如果说02是中间两位那么这个0显然可以省略，即02->2，但是与题设矛盾
     * 所以020实际上应该是：0020，
     * 最终得出：结果e0020->e00020。
     * <p>
     * 继续分析其它例子，
     * 比如0c00，看前两位0c，如果0c是RR，那么0c可以省略为c，但是前两位是0c，说明0c应该是00c，所以前两位是00
     * 中间两位是c0，现在剩最后一位0，补全为00，所以完整写法是00c000。
     *
     * @param rawColor
     * @return
     */
    public static String getStandardColor(String rawColor) {
        if (rawColor == null || !rawColor.startsWith("0x")) {
            throw new IllegalArgumentException("必须以0x开头的十六进制字符串");
        }
        String hex = rawColor.substring(2).toLowerCase();
        int length = hex.length();

        // 将rgb分为三块
        String r = "00", g = "00", b = "00";

        if (length == 6) {
            return "0x" + hex;
        }
        if (length == 5) {
            r = hex.substring(0, 2);
            // 如果r以0开头=> 肯定省略了1位，r则只占了一位且r=00，需要对剩下4位进行分析
            // r不已0开头，那么就没有进行省略，r就占了前两位，继续对g和b分析，需要对剩下3位进行分析
            if (r.startsWith("0")) {
                r = "00";
                // 现在剩余后面4位，不需要分析了，g和b各占2位
                g = hex.substring(1, 3);
                b = hex.substring(3, 5);
            }else {
                // 现在剩余后三位
                // 首先对g分析，如果g以0开头，那么g肯定为00，占了1位，b就是最后两位
                // 如果g不以0开头，那结果出了：b就是省略的那一位，即b= 0 + 最后一位
                g = hex.substring(2, 4);
                if (g.startsWith("0")) {
                    g = "00";
                    b = hex.substring(3, 5);
                } else{
                    b = "0" + hex.substring(length - 1);
                }
            }
        }

        if (length == 4) {
            r = hex.substring(0, 2);
            // 如果r以0开头=> r只占了1位，且r = 00，继续分析剩下的三位
            // r不已0开头，那么就没有进行省略，所以r占了前两位，剩余两位，直接在g和b左边补0即可
            if (r.startsWith("0")) {
                r = "00";
                // 现在剩余后面3位，先对g分析
                // g以0开头，那么g就占了一位，剩下的两位就是b了，所以g=00，b=最后两位
                // g不以0开头，则g占两位，最后一位就是b，b= "0" + 最后一位
                g = hex.substring(1, 3);
                if (g.startsWith("0")) {
                    g = "00";
                    b = hex.substring(2, 4);
                } else {
                    b = "0" + hex.substring(length - 1);
                }
            }else {
                // 现在剩余后2位，无需分析，b省略了1个0，g也省略了1个0，补全即可
                g = "0" + hex.substring(2,3);
                b = "0" + hex.substring(length - 1);
            }
        }

        if (length == 3) {
            // 这种情况三位都省略了，那么直接补0即可
            // 注意：COS中 #fff 为 #0f0f0f，不是#ffffff（这是css的简写）
            r = "0" + hex.charAt(0);
            g = "0" + hex.charAt(1);
            b = "0" + hex.charAt(2);
        }

        return "0x" + r + g + b;
    }
}
