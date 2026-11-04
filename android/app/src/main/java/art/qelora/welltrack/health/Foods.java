package art.qelora.welltrack.health;

import java.util.ArrayList;
import java.util.List;

/**
 * 常见食物热量表 (v2.3 饮食记录用):
 * kcalPer100g 为常见烹饪口径下的估算值 (参考《中国食物成分表》通行数据),
 * defaultGrams 是一份的默认克数, 用户在记录时可步进调整。
 * 实际入账热量以 kcalFor 计算并由用户确认, 仅作饮食估算参考。
 */
public class Foods {

    public static class Food {
        public final String name;
        public final String emoji;
        public final int kcalPer100g;
        public final int defaultGrams;

        public Food(String name, String emoji, int kcalPer100g, int defaultGrams) {
            this.name = name;
            this.emoji = emoji;
            this.kcalPer100g = kcalPer100g;
            this.defaultGrams = defaultGrams;
        }
    }

    public static final List<Food> ALL = new ArrayList<>();

    static {
        // 主食
        ALL.add(new Food("米饭", "🍚", 116, 150));
        ALL.add(new Food("面条", "🍜", 110, 200));
        ALL.add(new Food("馒头", "🍞", 221, 100));
        ALL.add(new Food("饺子", "🥟", 220, 180));
        ALL.add(new Food("燕麦", "🌾", 338, 50));
        ALL.add(new Food("红薯", "🍠", 86, 200));
        ALL.add(new Food("玉米", "🌽", 112, 160));
        // 快餐 / 高热量
        ALL.add(new Food("汉堡", "🍔", 250, 200));
        ALL.add(new Food("披萨", "🍕", 266, 120));
        ALL.add(new Food("薯条", "🍟", 312, 100));
        ALL.add(new Food("炸鸡", "🍗", 230, 150));
        // 肉蛋奶豆
        ALL.add(new Food("鸡胸肉", "🐔", 133, 120));
        ALL.add(new Food("牛肉（瘦）", "🥩", 125, 100));
        ALL.add(new Food("猪肉（瘦）", "🥓", 143, 100));
        ALL.add(new Food("清蒸鱼", "🐟", 110, 150));
        ALL.add(new Food("虾", "🦐", 93, 100));
        ALL.add(new Food("鸡蛋", "🥚", 144, 60));
        ALL.add(new Food("豆腐", "🍢", 81, 150));
        ALL.add(new Food("牛奶", "🥛", 54, 250));
        ALL.add(new Food("酸奶", "🥄", 72, 150));
        // 蔬果
        ALL.add(new Food("蔬菜沙拉", "🥗", 35, 150));
        ALL.add(new Food("西兰花", "🥦", 36, 150));
        ALL.add(new Food("炒青菜", "🥬", 60, 150));
        ALL.add(new Food("苹果", "🍎", 53, 180));
        ALL.add(new Food("香蕉", "🍌", 93, 120));
        ALL.add(new Food("橙子", "🍊", 48, 180));
        // 饮品
        ALL.add(new Food("可乐", "🥤", 43, 330));
        ALL.add(new Food("奶茶", "🧋", 65, 500));
    }

    /** 按每 100g 热量与克数计算入账热量 (四舍五入到整数千卡) */
    public static int kcalFor(Food food, int grams) {
        if (food == null || grams <= 0) return 0;
        return (int) Math.round(food.kcalPer100g * grams / 100.0);
    }

    /** 已知每份热量与克数时, 按克数同比例换算热量 (拍照结果改克数联动用) */
    public static int scaleKcal(int baseKcal, int baseGrams, int newGrams) {
        if (baseGrams <= 0 || newGrams <= 0) return baseKcal;
        return (int) Math.round(baseKcal * (double) newGrams / baseGrams);
    }
}
