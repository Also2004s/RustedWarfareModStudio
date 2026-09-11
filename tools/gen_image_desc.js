// 生成内置共享图像（shared_images.json）的中文说明 → data.value.image.*
// 规则（按序）：1) 全名 MANUAL 覆盖；2) 全名词表；3) 驼峰命中原版单位翻译；4) 前缀组合；5) 后缀剥离；6) 数字后缀；7) 基础词表兜底。
const fs = require('fs');
const path = 'd:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/assets/data';

const images = JSON.parse(fs.readFileSync(`${path}/shared_images.json`, 'utf8')).images;
const valueDesc = JSON.parse(fs.readFileSync(`${path}/value/value_desc.json`, 'utf8'));

// 删除旧的 data.value.image.*（重新生成）
for (const k of Object.keys(valueDesc)) if (k.startsWith('data.value.image.')) delete valueDesc[k];

// 原版单位：驼峰名 -> 中文
const VANILLA = {};
for (const k of Object.keys(valueDesc)) {
  if (k.startsWith('data.value.vanillaunit.')) VANILLA[k.replace('data.value.vanillaunit.', '')] = valueDesc[k];
}

const camel = (n) => n.split('_').map((s, i) => (i === 0 ? s : s.charAt(0).toUpperCase() + s.slice(1))).join('');

// 全名覆盖（难组合、易错的名字）
const MANUAL = {
  'antinuke_launcher': '反核弹发射器', 'antinuke_launcher_dead': '反核弹发射器残骸',
  'anti_air_top': '防空炮塔（顶部）', 'anti_air_top_flakgun': '防空高射炮塔（顶部）', 'anti_air_top_l2': '2级防空炮塔（顶部）',
  'artillery1': '火炮1', 'artillery2': '火炮2',
  'back_circle': '圆形返回按钮', 'backrepeat': '平铺重复背景', 'base_no_front': '基地（无正面）',
  'blood_mark': '血迹', 'cell_shape': '单元格形状',
  'battle_ship2': '战列舰2', 'battle_ship2_turret': '战列舰2炮塔', 'battle_ship_experimental': '实验战列舰', 'battle_ship_t2_turret': '战列舰T2炮塔',
  'btn_dropdown': '下拉按钮', 'btn_dropdown_normal': '下拉按钮（常规）', 'btn_dropdown_pressed': '下拉按钮（按下）', 'btn_dropdown_selected': '下拉按钮（选中）',
  'error_missingmap': '错误（缺失地图）', 'error_outmem': '错误（内存不足）', 'error_toolargethumb': '错误（缩略图过大）',
  'experimental_hovertank': '实验悬浮坦克', 'experimental_hovertank_shield': '实验悬浮坦克护盾',
  'experimental_unit_factory_base': '实验单位工厂底座', 'experimental_unit_factory_front': '实验单位工厂正面', 'experimental_unit_factory_dead': '实验单位工厂残骸',
  'explode_big2': '大爆炸2', 'explode_bits_bug': '虫族爆炸碎片',
  'helicopter_shadow_blades': '直升机旋翼阴影',
  'icon_back_round': '圆形返回图标', 'icon_rally': '集结图标', 'icon_round': '圆形图标', 'icon_save': '保存图标',
  'icon_search': '搜索图标', 'icon_upgrade': '升级图标', 'icon_window': '窗口图标', 'icon_window128': '窗口图标128',
  'icon_window16': '窗口图标16', 'icon_window24': '窗口图标24',
  'land_factory_front_t2': '陆军工厂正面T2', 'large_gunship': '大型武装直升机',
  'lock_icon_menu': '菜单锁定图标', 'light_50': '光照50', 'lava_bubble': '岩浆气泡',
  'metal_dark': '深色金属',
  'nuke_launcher': '核弹发射器', 'nuke_launcher2': '核弹发射器2', 'nuke_launcher_dead': '核弹发射器残骸',
  'palm_leaves': '棕榈叶', 'palm_tree': '棕榈树', 'plasma_shot': '等离子弹丸', 'projectiles_large': '大型抛射体',
  'replay_leaderboard': '回放排行榜', 'replay_pause': '回放暂停',
  'ripple_normal_128': '波纹（常规128）', 'shield_mid': '中型护盾', 'ship_scout_turret': '侦察船炮塔',
  'rounded_border': '圆角边框', 'rounded_dark_box': '深色圆角方框', 'rounded_dark_box_titled': '深色圆角方框（带标题）',
  'rounded_glow_button': '发光圆角按钮', 'rounded_glow_highlight_button': '发光高亮圆角按钮', 'rounded_green': '绿色圆角', 'rounded_white_button': '白色圆角按钮',
  'scorch_mark': '烧焦痕迹',
  'shockwave_large': '大型冲击波', 'shockwave_normal_128': '冲击波（常规128）', 'shockwave_normal_256': '冲击波（常规256）', 'shockwave_normal_64': '冲击波（常规64）',
  'small_trees': '小树', 'smoke_black_small': '小团黑烟',
  'stats_button_armyvalue': '统计按钮（军队价值）', 'stats_button_buildingvalue': '统计按钮（建筑价值）', 'stats_button_totalvalue': '统计按钮（总价值）',
  'temp_workaround_bug_image': '临时占位图（游戏bug占位）', 'trees_snow': '雪地树木', 'touch_indicator': '触摸指示',
  'turret_top_artillery': '火炮炮塔顶部', 'turret_top_flame': '火焰炮塔顶部',
  'unit_icon_building_air_turrent': '单位图标（建筑防空炮塔）', 'unit_icon_building_turrent': '单位图标（建筑炮塔）',
  'wall_b': '砖墙', 'wall_h': '横向墙', 'wall_v': '垂直墙',
  'water_cloud': '水花', 'water_layer1': '水面层1', 'water_layer2': '水面层2', 'zoom_button': '缩放按钮',
};

// 词表
const DICT = {
  factory: '工厂', jet: '喷气机', gunship: '武装直升机', dropship: '运输机', helicopter: '直升机',
  tank: '坦克', ship: '船', submarine: '潜艇', launcher: '发射器', nuke: '核弹', antinuke: '反核弹',
  artillery: '火炮', turret: '炮塔', extractor: '资源提取器', builder: '建造者', hover: '悬浮',
  hovercraft: '悬浮艇', power: '发电机', land: '陆军', sea: '海军', air: '空军', repair: '维修',
  bay: '站', supply: '补给', depot: '仓库', crystal: '晶体', explode: '爆炸', shockwave: '冲击波',
  smoke: '烟雾', flame: '火焰', fire: '火焰', water: '水', tree: '树', trees: '树木', palm: '棕榈',
  leaves: '树叶', lava: '岩浆', bubble: '气泡', scorch: '烧焦', mark: '痕迹', blood: '血迹',
  border: '边框', rounded: '圆角', box: '方框', glow: '发光', highlight: '高亮', button: '按钮',
  btn: '按钮', icon: '图标', stats: '统计', error: '错误', general: '通用', back: '返回',
  menu: '菜单', fast: '快速', dust: '尘土', fog: '迷雾', dithering: '抖动', smooth: '平滑',
  ripple: '波纹', noise: '噪点', cell: '单元格', shape: '形状', lock: '锁定', zoom: '缩放',
  touch: '触摸', indicator: '指示', pointer: '指针', title: '标题', logo: '标志', help: '帮助',
  pause: '暂停', replay: '回放', leaderboard: '排行榜', income: '收入', info: '信息',
  relative: '相对', teams: '队伍', underwater: '水下', experimental: '实验', unit: '单位',
  hovertank: '悬浮坦克', hover_tank: '悬浮坦克', heavy: '重型', mega: '巨型', mammoth: '猛犸',
  laser: '激光', plasma: '等离子', missile: '导弹', attack: '攻击', scout: '侦察', boat: '艇',
  battle: '战列舰', antiair: '防空', lighting: '闪电', charge: '充能', shot: '弹丸',
  big: '大型', bits: '碎片', normal: '常规', small: '小型', large: '大型',
  black: '黑色', orange: '橙色', red: '红色', green: '绿色', white: '白色', dark: '深色',
  add: '添加', more: '更多', yes: '是', no: '否', subtract: '减少',
  dropdown: '下拉', pressed: '按下', selected: '选中', round: '圆形', rally: '集结',
  save: '保存', search: '搜索', upgrade: '升级', window: '窗口', cloud: '云',
  flame_large: '大型火焰', fog_smooth: '平滑迷雾', fog_dithering: '抖动迷雾',
  base: '基地', backrepeat: '平铺重复背景', battle_ship: '战列舰', unit_factory: '单位工厂',
  repair_bay: '维修站', scout_ship: '侦察船', turret_top: '炮塔顶部', ship_scout: '侦察船',
  metal: '金属', effects: '特效图集', projectiles: '抛射体图集', custombutton: '自定义按钮',
  scorch_mark_nuke: '核弹烧焦痕迹', smoke_black: '黑烟', smoke_white: '白烟', queenbug: '虫后',
  ladybug: '瓢虫', tank1: '坦克1', tank2: '坦克2', turret_base: '炮塔底座',
};

// 后缀表（从长到短）
const SUFFIX = [
  [/^(.*)_t2_dead$/, 'T2残骸'], [/^(.*)_t3_dead$/, 'T3残骸'],
  [/^(.*)_t2$/, 'T2'], [/^(.*)_t3$/, 'T3'],
  [/^(.*)_decharge$/, '放能'], [/^(.*)_charge$/, '充能'],
  [/^(.*)_flakgun$/, '高射炮'], [/^(.*)_blades$/, '旋翼'],
  [/^(.*)_shadow$/, '阴影'], [/^(.*)_turrent$/, '炮塔'], [/^(.*)_turret$/, '炮塔'],
  [/^(.*)_dead$/, '残骸'], [/^(.*)_front$/, '正面'], [/^(.*)_back$/, '背面'],
  [/^(.*)_shield$/, '护盾'], [/^(.*)_base$/, '底座'], [/^(.*)_top$/, '顶部'],
  [/^(.*)_p1$/, '部件1'], [/^(.*)_p2$/, '部件2'],
  [/^(.*)_l2$/, '2级'], [/^(.*)_l3$/, '3级'],
];

// 前缀组合
const SUFFIX_FULL = [
  [/^unit_icon_(.*)$/, (m) => `单位图标（${ICON_TAG[m[1]] || DICT[m[1]] || MANUAL[m[1]] || m[1]}）`],
  [/^stats_button_(.*)$/, (m) => `统计按钮（${DICT[m[1]] || m[1]}）`],
  [/^stats_toggle_(.*)$/, (m) => `统计切换（${DICT[m[1]] || m[1]}）`],
  [/^btn_(.*)$/, (m) => `按钮（${DICT[m[1]] || m[1]}）`],
  [/^button_(.*)$/, (m) => `按钮（${DICT[m[1]] || m[1]}）`],
  [/^icon_(.*)$/, (m) => `图标（${DICT[m[1]] || m[1]}）`],
  [/^error_(.*)$/, (m) => `错误（${DICT[m[1]] || m[1]}）`],
  [/^explode_(.*)$/, (m) => `爆炸（${DICT[m[1]] || m[1]}）`],
  [/^shockwave_(.*)$/, (m) => `冲击波（${DICT[m[1]] || m[1]}）`],
  [/^smoke_(.*)$/, (m) => `烟雾（${DICT[m[1]] || m[1]}）`],
  [/^water_(.*)$/, (m) => `水面（${DICT[m[1]] || m[1]}）`],
  [/^help([0-9]+)$/, (m) => `帮助${m[1]}`],
];

const ICON_TAG = { air: '空军', builder: '建造者', building: '建筑', land: '陆军', hover: '悬浮', water: '水面', underwater: '水下', land_exp: '实验陆军' };

function translateBase(n) {
  if (MANUAL[n]) return MANUAL[n];
  if (DICT[n]) return DICT[n];
  if (VANILLA[camel(n)]) return VANILLA[camel(n)];
  const words = n.split('_');
  for (let i = words.length - 1; i >= 0; i--) {
    if (DICT[words.slice(0, i + 1).join('_')]) return DICT[words.slice(0, i + 1).join('_')];
  }
  return null;
}

function translate(name) {
  if (MANUAL[name]) return MANUAL[name];
  if (DICT[name]) return DICT[name];
  const c = camel(name);
  if (VANILLA[c]) return VANILLA[c];
  for (const [re, fn] of SUFFIX_FULL) {
    const m = re.exec(name);
    if (m) return typeof fn === 'string' ? name.replace(re, fn) : fn(m);
  }
  for (const [re, suf] of SUFFIX) {
    const m = re.exec(name);
    if (m) {
      const base = translateBase(m[1]);
      if (base) return `${base}${suf}`;
    }
  }
  // 数字后缀
  const dm = /^(.*?)([0-9]+)$/.exec(name);
  if (dm && dm[1] !== name) {
    const base = translateBase(dm[1]);
    if (base) return `${base}${dm[2]}`;
  }
  return null;
}

const out = {};
let covered = 0, unknown = 0;
for (const img of images) {
  const zh = translate(img);
  if (zh) { out[img] = zh; covered++; }
  else { out[img] = `${img}（图像）`; unknown++; }
}

for (const [k, v] of Object.entries(out).sort()) console.log(`${k} = ${v}`);
console.error(`\n# total=${images.length} covered=${covered} unknown=${unknown}`);
for (const [k, v] of Object.entries(out)) valueDesc[`data.value.image.${k}`] = v;
fs.writeFileSync(`${path}/value/value_desc.json`, JSON.stringify(valueDesc, null, 2) + '\n');
console.error('# written to value_desc.json');
