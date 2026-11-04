package art.qelora.welltrack.health;

import java.util.ArrayList;
import java.util.List;

/**
 * 运动库 (v2.10 R8, 对标 Keep / 小米运动健康):
 * 记录运动时按项目选择, 每项带 MET 值 (代谢当量, 通行参考值),
 * 消耗按标准公式估算: 千卡 = MET × 体重(kg) × 时长(小时)。
 * 估算仅供参考, 不作为精确热量计量; 体重取用户最近一次记录,
 * 未填过体重时按 60 kg 默认估算并在界面如实标注。
 * 后端契约不变: 项目名仍存入 exercises.kind, 估算结果写进备注,
 * 不新增接口与字段, 历史记录在旧后端上同样可读。
 */
public class Sports {

    public static class Sport {
        public final String name;
        public final String emoji;
        public final double met;

        public Sport(String name, String emoji, double met) {
            this.name = name;
            this.emoji = emoji;
            this.met = met;
        }
    }

    public static final List<Sport> ALL = new ArrayList<>();

    static {
        ALL.add(new Sport("跑步", "🏃", 8.0));
        ALL.add(new Sport("快走", "🚶", 4.3));
        ALL.add(new Sport("步行", "🚶", 3.5));
        ALL.add(new Sport("骑行", "🚴", 7.5));
        ALL.add(new Sport("游泳", "🏊", 8.0));
        ALL.add(new Sport("瑜伽", "🧘", 3.0));
        ALL.add(new Sport("跳绳", "🤸", 10.0));
        ALL.add(new Sport("力量训练", "🏋️", 6.0));
        ALL.add(new Sport("拉伸", "🧘", 2.5));
        ALL.add(new Sport("羽毛球", "🏸", 5.5));
        ALL.add(new Sport("篮球", "⛹", 6.5));
        ALL.add(new Sport("足球", "⚽", 7.0));
        ALL.add(new Sport("爬山", "🥾", 6.0));
        ALL.add(new Sport("舞蹈", "💃", 5.0));
        ALL.add(new Sport("HIIT", "🔥", 8.0));
    }

    /** 按名查找, 找不到 (如旧的「有氧运动」等笼统类型) 返回 null, 调用方走旧口径 */
    public static Sport find(String name) {
        if (name == null) return null;
        for (Sport s : ALL) {
            if (s.name.equals(name)) return s;
        }
        return null;
    }

    /** MET 估算消耗 (千卡, 四舍五入); 任一输入非法返回 -1 */
    public static int kcalFor(Sport sport, double weightKg, int minutes) {
        if (sport == null || weightKg <= 0 || minutes <= 0) return -1;
        return (int) Math.round(sport.met * weightKg * minutes / 60.0);
    }
}
