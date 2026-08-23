// ===== 以下名单由 tools/gen_completion_types.js 从 code_reference.json 生成（勿手改名单本身，
//      数据或判定变更后重跑脚本再整体替换）；运行时只做名单查找，不按 type 推断 =====

/** 布尔值 分类属性（271 个，键=英文小写） */
val BOOL_PROPERTIES = setOf(
    "@copyfrom_skipthissection", "action_#_whenbuilding_cannotmove", "addtransportedunits", "addwaypoint_position_fromaction",
    "addwaypoint_prepend", "addwaypoint_target_mapmustbereachable", "ai_considersameasbuilding", "allowcapturewhenneutralbyai",
    "allowmultipleinqueue", "alwaysallowedtoattackparentsmaintarget", "alwayssinglepress", "alwaysstartdiratzero",
    "alwaystartdiratzero", "alwaysvisibleinfog", "animateframelooping", "animation_type_pingpong",
    "appendresourceinhud_whenthiszero", "areadamagenofalloff", "areahitairandlandatsametime", "areahitunderwateralways",
    "arearadiusfromedge", "atmospheric", "attachedtounit", "attachments_disconnect",
    "attachments_unload", "autorepair", "autotargetingondeadtarget", "autotriggercheckwhilenotbuilt",
    "autotriggercooldowntime_allowdangeroushighcpu", "ballistic", "beamimageendrotated", "beamimagestartrotated",
    "blocksplacement", "builtfrom_{num}_forcenano", "canattack", "canbeattackedanddamaged",
    "canbuild_#_islocked", "cannotbedamaged", "cannotbedirectlyattacked", "cannotbegivenordersbyplayer",
    "canplayercancel", "canreclaimresources", "canrepairbuildings", "canrepairunits",
    "canshoot", "checkeachtile", "clearactivewaypoint", "clearallactioncooldowns",
    "clearallwaypoints", "clearturrettargetafterfiring", "converthittosourceteam", "convertto_keepcurrenttags",
    "converttoneutralifnottransporting", "createincompleteifparentis", "createneutral", "createonaggressiveteam",
    "createwhenoffscreen", "createwhenoverland", "createwhenoverliquid", "createwhenzoomedout",
    "deattachifwantingtomove", "deleteself", "dieonattack", "dieonconstruct",
    "dieonzeroenergy", "disableallunitcollisions", "disabledeathonzerohp", "disableleadtargeting",
    "disablelowhpfire", "disablelowhpsmoke", "disablepassivetargeting", "disableuse",
    "disconnectfromparent", "displaycoloruseintext", "displayinhud", "displaynamehidewheniconshowninhud",
    "displaynamehidewheniconshownintext", "displayremainingstockpile", "displayroundeddown", "displaytextappendresourcewithgap",
    "displaywhenzero", "displaywithrounding", "dont_load", "draw_foot_on_top",
    "drawfootwhenzoomedout", "drawlegwhenzoomedout", "drawoverbody", "drawunderallunits",
    "drawunderunits", "dust_effect", "dusteffect", "dusteffectreverse",
    "energyneedstorechargetofull", "equivalentglobalresourceforai", "excludeincompletebuildings", "excludenonbuildings",
    "exitheightignoreparent", "experimental", "explodeondeath", "explodeondeathgroundcollision",
    "explodeonendoflife", "fadeout", "favouroppositesideneighbours", "finishplayinglastanimation",
    "flameweapon", "forcenano", "forceunloadtransportnow", "frameindexrandom",
    "friendlyfire", "hidden", "hidescorchmark", "highpriorityqueue",
    "hitsound", "hold_moveonlyiffurthest", "holddismin_checkneighbours", "icon_zoomed_out_nevershow",
    "iconimageuseintext", "ignoreinunitcapcalculation", "ignoreparentshootdamagemultiplier", "image_applyteamcolors",
    "image_end_teamcolors", "image_shadow_frames", "includeinstats", "includeparentsselection",
    "instant", "instantreuselast", "instantreuselast_alsochangeturretaim", "instantreuselast_keepareadamagelist",
    "interceptprojectile_removetargetlifeonly", "invisible", "isalsoviewablebyallies", "isalsoviewablebyenemies",
    "isbio", "isbug", "isbuilder", "isbuilding",
    "isfixedfiring", "islockedifgamemodenonuke", "ismainnanoturret", "ismelee",
    "ispickablestartingunit", "isunrepairableunit", "isunselectable", "isunselectableastarget",
    "isvisibletoenemies", "joinsgroupformations", "keepalivewhenparentdies", "keepwaypointsneedingmovement",
    "landonground", "largehiteffect", "lasereffect", "lightcastonground",
    "lightingeffect", "liveafterattacheddies", "lock_body_rotation_with_main_turret", "lock_leg_rotation_with_main_turret",
    "lock_shadow_rotation_with_main_turret", "locklegmovement", "lockmovement", "lockrotation",
    "lowprioritytargetforotherunits", "moveignoringbody", "movementeffectreverseflipeffects", "moveslidingmode",
    "movewithparent", "nanorangeforreclaimismelee", "nanorangeforrepairismelee", "notpassivelytargetedbyotherunits",
    "nukeondeath", "nukeondeathdisablewhennonuke", "nukeweapon", "onconvertkeepexistingunitinsameslot",
    "onlyinpreview", "onlyoneunitatatime", "onlyonnonpreview", "onlyplayerswithunitcontrol",
    "onlywhenselectedbyallynotownplayer", "onlywhenselectedbyanyplayer", "onlywhenselectedbyenemyplayer", "onlywhenselectedbyownplayer",
    "onlywhileactive", "onlywhilealive", "onparentteamchangekeepcurrentteam", "physics",
    "pingpong", "placeonlyonrespool", "playanimationifnotplaying", "prioritizeparentsmaintarget",
    "redirectdamagetoparent", "redirectdamagetoparent_shieldonly", "refundallqueueditems", "removeallqueueditemswithoutrefund",
    "resetrotationwhennotattacking", "resettodefaulttags", "resetunitstats", "retargetinginflight",
    "rotate_with_direction", "rotatewithparent", "setdrawlayeronbottom", "setdrawlayerontop",
    "shadow", "shielddisplayonlydeflection", "shouldresetturret", "shouldrevealfog",
    "showenergybar", "showhealthbar", "showineditor", "showinfog",
    "showonminimap", "showonminimaptoenemies", "showqueuebar", "showrangeuiguide",
    "showselectionindicator", "showshieldbar", "showshotdelaybar", "showtransportbar",
    "slave", "slowdeathfall", "smoothlyblendpositionwhenexistingunitadded", "splasteffect",
    "splasteffectreverse", "stack_drawinreverseorder", "stackhorizontal", "startfallingwhenstartingunit",
    "startshieldatzero", "startunloadingtransport", "stayneutral", "stoplastanimation",
    "stoptargetingafterfiring", "switchpricewithstreamingcost", "switchtoaggressiveteam", "switchtoneutralteam",
    "takeresources_directtransferstoppingatzero", "takeresources_discardcollected", "takeresources_excludeunitswithoutallresources", "takeresources_includeparent",
    "takeresources_includeunitsintransport", "takeresources_keepresourcesontarget", "takeresources_searchonly", "targetground",
    "targetground_includetargetheight", "targetheightrelative", "teamcolors", "teamcolorsonturret",
    "teamcolorsusehue", "teleportsource", "traileffect", "transportunitsaddunloadoption",
    "transportunitsblockairandwaterunits", "transportunitsblockothertransports", "transportunitseachunitalwaysusessingleslot", "transportunitskeepbuiltunits",
    "transportunitsonteamchangekeepcurrentteam", "turretmultitargeting", "turretrotatewithbody", "unitshowninuiwithhpbar",
    "unitshowninuiwithprogressbar", "unitsspawnedondeath_settoteamoflastattacker", "unloadincurrentposition", "useasbuilder",
    "useasharvester", "useastransport", "valueinstats", "warmupnoreset",
    "whenbuilding_cannotmove", "whenbuilding_rotateto_aimatactiontarget", "whenbuilding_rotateto_orbackwards", "whenbuilding_rotateto_waittillrotated",
    "whenusingasharvester_includeotherharvestercounts", "whileneutralconverttotransportedteam", "whileneutraltransportanyteam"
)

/** 逻辑表达式 分类属性（37 个，键=英文小写） */
val LOGIC_PROPERTIES = setOf(
    "ai_isdisabled", "ai_ishighpriority", "alpha", "alsotriggeractionrepeat",
    "alsotriggerorqueueactionconditional", "altprojectilecondition", "autotrigger", "builtfrom_{num}_islocked",
    "canattackcondition", "canattackflyingunits", "canattacklandunits", "canattacknottouchingwaterunits",
    "canattackunderwaterunits", "frame", "generation_active", "iconextraisvisible",
    "idlesweepcondition", "imagescale", "imagescalex", "imagescaley",
    "isactive", "isguiblinking", "islocked", "islockedalt",
    "islockedalt2", "isvisible", "requireconditional", "resetcustomtimer",
    "setheight", "showallactionsfrom", "stack_indexcount", "stack_indexstart",
    "switchtoteam", "transportunitscanunloadunits", "transportunitskillondeath", "xoffsetabsolute",
    "yoffsetabsolute"
)

/** 枚举值 分类属性（22 个，键=英文小写） */
val ENUM_PROPERTIES = setOf(
    "addwaypoint_target_nearestunit_team", "addwaypoint_target_randomunit_team", "addwaypoint_type", "attackmovement",
    "autotriggercheckrate", "convertto_keepcurrentfields", "displaydigitgrouping", "displaytype",
    "drawlayer", "drawtype", "fireturretxatground_onlyoverpassabletileof", "layer",
    "movementtype", "onactions", "onlyteam", "onnewmapspawn",
    "searchteam", "setunitstats", "takeresources_includeunitswithinrange_team", "teamcoloringmode",
    "transportunitsrequiremovementtype", "whenbuilding_temporarilyconvertto_keepfields"
)

/** 图片路径 分类属性（31 个，键=英文小写） */
val IMAGE_PROPERTIES = setOf(
    "beamimage", "beamimageend", "beamimageoffsetrate", "beamimagestart",
    "chargeeffectimage", "icon_build", "icon_zoomed_out", "iconextraimage",
    "iconimage", "image", "image_back", "image_drawoffsetx",
    "image_drawoffsety", "image_end", "image_end_shadow", "image_floatingpointsize",
    "image_foot", "image_foot_shadow", "image_leg", "image_middle",
    "image_offseth", "image_offsetx", "image_offsety", "image_shadow",
    "image_shield", "image_turret", "image_wreak", "imageshadow",
    "imagestack", "scaleimagesto", "scaleturretimagesto"
)

/** 事件触发 分类属性（1 个，键=英文小写） */
val EVENT_PROPERTIES = setOf(
    "autotriggeronevent"
)

/** 内存变量 分类属性（5 个，键=英文小写） */
val MEMORY_PROPERTIES = setOf(
    "@memory", "defineunitmemory", "setunitmemory", "updateunitmemory",
    "updateunitmemoryrate"
)

/** 标签 分类属性（24 个，键=英文小写） */
val TAG_PROPERTIES = setOf(
    "addglobalteamtags", "addwaypoint_target_nearestunit_tagged", "addwaypoint_target_randomunit_tagged", "canonlyattackunitswithouttags",
    "canonlyattackunitswithtags", "canonlybeattackedbyunitswithtags", "canreclaimresourcesonlywithtags", "canreclaimunitsonlywithtags",
    "canrepairunitsonlywithtags", "deletenumunitsfromtransport_onlywithtags", "interceptprojectiles_withtags", "mutatorx_ifunitwithouttags",
    "mutatorx_ifunitwithtags", "removeglobalteamtags", "retargetinginflightsearchonlytags", "searchtags",
    "sendmessagewithtags", "showactionswithmixedselectionifotherunitshavetag", "similarresourceshavetag", "tags",
    "takeresources_excludeunitswithouttags", "temporarilyaddtags", "temporarilyremovetags", "transportunitsrequiretag"
)

/** 资源 分类属性（20 个，键=英文小写） */
val RESOURCE_PROPERTIES = setOf(
    "addresources", "addresourcesscaledbyaihandicaps", "addresourceswithlogic", "appendresourceinhud",
    "borrowresourceswhilealive", "borrowresourceswhilebuilt", "convertresource_from", "convertresource_to",
    "displaytextappendresource", "generation_resources", "mutatorx_addresourcesareahit", "mutatorx_addresourcesdirecthit",
    "price", "resourceamount", "resourceamount_addotherresource", "resourceusage",
    "setresourceswithlogic", "streamingcost", "takeresources", "takeresources_excludeunitswiththeseresources"
)

/** 单位名 分类属性（18 个，键=英文小写） */
val UNIT_NAME_PROPERTIES = setOf(
    "addunitsintotransport", "addwaypoint_unittype", "attachments_addnewunits", "convertto",
    "descriptionaddunitstats", "guibuildunit", "name", "oncreatespawnunitof",
    "overrideandreplace", "produceunits", "spawnprojectilesoncreate", "spawnprojectilesonendoflife",
    "spawnprojectilesonexplode", "spawnunit", "spawnunits", "unitsspawnedondeath",
    "upgradedfrom", "whenbuilding_temporarilyconvertto"
)

/** 单位标记 分类属性（14 个，键=英文小写） */
val UNIT_MARKER_PROPERTIES = setOf(
    "addwaypoint_target_fromreference", "alsotriggerorqueueactionwithtarget", "baseposition", "descriptionaddfromunit",
    "drawlineto", "fireturretxatground_withtarget", "sendmessageto", "setcustomtarget1",
    "setcustomtarget2", "takeresources_includereference", "teleportto", "textaddunitname",
    "transporttargetnow", "unitshowninui"
)

/** 项目引用 分类属性（52 个，键=英文小写） */
val PROJECT_REF_PROPERTIES = setOf(
    "addactioncooldownapplytoactions", "addwaypoint_triggeractioniffailed", "addwaypoint_triggeractionifmatched", "alsoemiteffects",
    "alsoemiteffectsondeath", "alsoplaysound", "alsoqueueaction", "alsotriggeraction",
    "altprojectile", "attachedto", "attachments_onlyonslots", "basepositionfromturret",
    "effectoncreate", "effectondeath", "effectondeathgroundcollision", "effectondeathifunbuilt",
    "explodeeffect", "explodeeffectonshield", "fireturretxatground", "fireturretxatground_showguidedecals",
    "fireturretxatground_withprojectile", "fireturretxatselfondeath", "ifspawnfailsemiteffects", "linkdelaywithturret",
    "movementeffect", "movementeffectreverse", "mutatorx_changedexplodeeffect", "onshoot_playanimation",
    "onshoot_triggeractions", "playanimation", "playsoundatunit", "playsoundglobally",
    "playsoundtoplayer", "playsoundtoplayeronqueue", "projectile", "reclaimeffect",
    "reclaimeffectattarget", "repaireffect", "repaireffectattarget", "shoot_flame",
    "soundonattackorder", "soundonmoveorder", "soundonnewselection", "spawneffects",
    "spawneffectsonqueue", "takeresources_triggeractionforeach", "takeresources_triggeractionifanycollected", "takeresources_triggeractionifnonecollected",
    "warmupstarteffect", "whenbuilding_playanimation", "whenbuilding_rotateto_rotateturretx", "whenbuilding_triggeraction"
)
