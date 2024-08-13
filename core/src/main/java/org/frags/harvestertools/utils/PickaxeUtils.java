package org.frags.harvestertools.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitScheduler;
import org.frags.harvestertools.HarvesterTools;
import org.frags.harvestertools.enchants.CustomEnchant;
import org.frags.harvestertools.enchants.EnchantsManager;
import org.frags.harvestertools.enums.Tools;
import org.frags.harvestertools.managers.LevelManager;
import org.frags.harvestertools.managers.MessageManager;
import org.frags.harvestertools.objects.Block;
import org.frags.harvestertools.objects.Level;
import org.frags.harvestertools.objects.Mob;

import java.math.BigDecimal;
import java.util.*;

public class PickaxeUtils {

    private final HarvesterTools plugin;
    private final EnchantsManager enchantsManager;

    private final Set<Player> autoSellKey = new HashSet<>();
    private final Set<Player> experienceKey = new HashSet<>();
    private final Set<Player> collectKey = new HashSet<>();
    private final HashMap<Player, Double> moneyMap = new HashMap<>();
    private final HashMap<Player, Double> essenceMap = new HashMap<>();
    private final HashMap<Player, Double> experienceMap = new HashMap<>();

    private final HashMap<UUID, Long> lastHasteTime = new HashMap<>();
    private final HashMap<UUID, Long> lastSpeedTime = new HashMap<>();

    private final FileConfiguration configuration;
    private final ConfigurationSection section;

    private double initialMoney = 0D;
    private double initialEssence = 0D;
    private double initialExperience = 0D;

    private final long cooldownTime = 5000;


    public PickaxeUtils(HarvesterTools plugin) {
        this.plugin = plugin;
        this.enchantsManager = plugin.getEnchantsManager();
        this.configuration = plugin.pickaxeEnchantsFile.getConfig();
        this.section = configuration.getConfigurationSection("CustomEnchants");
    }

    private void getInitialPrices(double money, double essence, double experience) {
        this.initialMoney = money;
        this.initialEssence = essence;
        this.initialExperience = experience;
    }

    public void calculateAutoSellDrops(ItemStack itemStack, Player player, Block block, Collection<ItemStack> drops) {

        moneyMap.putIfAbsent(player, 0D);
        essenceMap.putIfAbsent(player, 0D);
        experienceMap.putIfAbsent(player, 0D);

        double initialEssencePrice = 0D;
        double initialMoneySell = 0D;
        double initialXP = 0D;
        //Has the enchant -
        //See the mode:

        if (ToolUtils.isAutoSell(itemStack)) {
            //Is activated

            for (int i = 0; i < drops.size(); i++) {
                initialMoneySell += block.getMoney();
                initialEssencePrice += block.getEssence();
                initialXP += block.getExperience();
            }

            getInitialPrices(initialMoneySell, initialEssencePrice, initialXP);

            addAmountToMaps(player, initialMoneySell, initialEssencePrice, initialXP);

            long autoSellTime = configuration.getLong("CustomEnchants.autosell.autosell-time") * 20;

            if (!autoSellKey.contains(player)) {
                autoSellKey.add(player);
                BukkitScheduler scheduler = Bukkit.getServer().getScheduler();
                scheduler.runTaskLater(plugin, () -> {
                    double moneyL = moneyMap.get(player);
                    double essenceL = essenceMap.get(player);
                    ConfigurationSection actionBar = section.getConfigurationSection("autosell.actionbar");
                    if (actionBar.getBoolean("enabled")) {
                        String message = MessageManager.miniStringParse(actionBar.getString("message"))
                                .replace("%money%", Utils.formatNumber(BigDecimal.valueOf(moneyL)))
                                .replace("%essence%", Utils.formatNumber(BigDecimal.valueOf(essenceL)));
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
                    }
                    ConfigurationSection title = section.getConfigurationSection("autosell.title");
                    if (title.getBoolean("enabled")) {
                        String titleMessage = MessageManager.miniStringParse(title.getString("title"))
                                .replace("%money%", Utils.formatNumber(BigDecimal.valueOf(moneyL)))
                                .replace("%essence%", Utils.formatNumber(BigDecimal.valueOf(essenceL)));
                        String subtitle = MessageManager.miniStringParse(title.getString("subtitle"))
                                .replace("%money%", Utils.formatNumber(BigDecimal.valueOf(moneyL)))
                                .replace("%essence%", Utils.formatNumber(BigDecimal.valueOf(essenceL)));
                        int fadeIn = title.getInt("fadeIn");
                        int fadeOut = title.getInt("fadeOut");
                        int time = title.getInt("time");
                        player.sendTitle(titleMessage, subtitle, fadeIn, time, fadeOut);
                    }

                    if (plugin.canUseVault) {
                        //Give money
                        plugin.getEcon().depositPlayer(Bukkit.getOfflinePlayer(player.getUniqueId()), moneyL);
                    }

                    plugin.getEssenceManager().addEssence(player, essenceL);

                    ItemMeta meta = itemStack.getItemMeta();

                    PersistentDataContainer container = meta.getPersistentDataContainer();

                    double essence = container.getOrDefault(ToolUtils.essenceKey, PersistentDataType.DOUBLE, 0.0D);

                    container.set(ToolUtils.essenceKey, PersistentDataType.DOUBLE, essence + essenceL);

                    moneyMap.remove(player);
                    essenceMap.remove(player);
                    autoSellKey.remove(player);
                }, autoSellTime);
            }
        } else {

            for (ItemStack drop : drops) {
                player.getInventory().addItem(drop);
                initialEssencePrice += block.getEssence();
                initialXP += block.getExperience();
            }


            getInitialPrices(initialMoneySell, initialEssencePrice, initialXP);

            addAmountToMaps(player, 0, initialEssencePrice, initialXP);
            if (!collectKey.contains(player)) {
                collectKey.add(player);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    collectKey.remove(player);
                    plugin.getEssenceManager().addEssence(player, essenceMap.get(player));
                    essenceMap.remove(player);
                }, 5 * 20);
            }

        }
    }

    public void procSpeed(Player player, ItemStack itemStack) {
        UUID playerUUID = player.getUniqueId();

        CustomEnchant speed = enchantsManager.getEnchant("speed", Tools.hoe);
        if (speed == null)
            return;

        if (!enchantsManager.hasEnchantment(itemStack, speed))
            return;

        int level = enchantsManager.getEnchantmentLevel(itemStack, speed);

        long currentTime = System.currentTimeMillis();

        if (lastSpeedTime.containsKey(playerUUID)) {
            long lastTime = lastSpeedTime.get(playerUUID);

            if ((currentTime - lastTime) < cooldownTime) {
                return;
            }
        }
        PotionEffect effect = new PotionEffect(PotionEffectType.SPEED, 200, level);
        player.addPotionEffect(effect);

        lastSpeedTime.replace(playerUUID, currentTime);
    }

    public void calculateBoosters(Player player, ItemStack itemStack) {
        double moneyToAdd = 0D;
        double essenceToAdd = 0D;
        double experienceToAdd = 0D;

        PersistentDataContainer container = itemStack.getItemMeta().getPersistentDataContainer();

        double moneyPrestigeBooster = container.getOrDefault(ToolUtils.moneyBoostKey, PersistentDataType.DOUBLE, 0D);
        double essencePrestigeBooster = container.getOrDefault(ToolUtils.essenceBoostKey, PersistentDataType.DOUBLE, 0D);
        CustomEnchant moneyBooster = plugin.getEnchantsManager().getEnchant("moneybooster", Tools.sword);
        if (moneyBooster != null) {
            if (enchantsManager.hasEnchantment(itemStack, moneyBooster)) {
                int level = enchantsManager.getEnchantmentLevel(itemStack, moneyBooster);
                double boost = moneyBooster.getBoostPerLevel() * level;

                moneyToAdd = boost * initialMoney;
            }
        }


        CustomEnchant essenceBoost = plugin.getEnchantsManager().getEnchant("essencebooster", Tools.sword);
        if (essenceBoost != null) {
            if (enchantsManager.hasEnchantment(itemStack, moneyBooster)) {
                int level = enchantsManager.getEnchantmentLevel(itemStack, moneyBooster);
                double boost = essenceBoost.getBoostPerLevel() * level;

                essenceToAdd = boost * initialEssence;

            }
        }

        CustomEnchant experienceBoost = plugin.getEnchantsManager().getEnchant("experiencebooster", Tools.sword);
        if (experienceBoost != null) {
            if (enchantsManager.hasEnchantment(itemStack, experienceBoost)) {
                int level = enchantsManager.getEnchantmentLevel(itemStack, experienceBoost);
                double boost = experienceBoost.getBoostPerLevel() * level;

                experienceToAdd = boost * initialExperience;
            }
        }

        addAmountToMaps(player,
                moneyToAdd + (moneyToAdd * moneyPrestigeBooster),
                essenceToAdd + (essenceToAdd * essencePrestigeBooster),
                experienceToAdd);
    }

    public void addExperience(Player player, ItemStack itemStack) {
        if (ToolUtils.isMaxLevel(itemStack))
            return;
        if (!experienceKey.contains(player)) {

            double experience = experienceMap.getOrDefault(player, 0D);
            experienceKey.add(player);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                experienceKey.remove(player);
            }, 100L);

            Tools tools = ToolUtils.getTool(itemStack);

            double toolLevel = ToolUtils.getItemLevel(itemStack);
            double toolExperience = ToolUtils.getItemExperience(itemStack);

            LevelManager levelManager = plugin.getLevelManager();

            Level level = levelManager.getLevel(tools);

            //starting-xp + starting-xp * (level * increment-xp)

            double nextLevelXP = level.getStartingXP() + level.getStartingXP() * (toolLevel * level.getIncrementXP());

            if (toolExperience + experience >= nextLevelXP) {
                //Item goes to next level.
                ToolUtils.setLevel(itemStack, 1);
                ToolUtils.setExperience(itemStack, 0D);
                MessageManager.miniMessageSender(player, plugin.messages.getConfig().getString("level-up-tool").replace("%level%", String.valueOf((int) toolLevel + 1)));
                ToolUtils.updateVariables(itemStack);
                return;
            }

            //Adds experience
            ToolUtils.setExperience(itemStack, toolExperience + experience);
        }
    }

    public void procHaste(Player player, ItemStack itemStack) {
        UUID playerUUID = player.getUniqueId();

        CustomEnchant haste = enchantsManager.getEnchant("haste", Tools.pickaxe);
        if (haste == null)
            return;

        if (!enchantsManager.hasEnchantment(itemStack, haste))
            return;

        int level = enchantsManager.getEnchantmentLevel(itemStack, haste);
        long currentTime = System.currentTimeMillis();

        if (lastHasteTime.containsKey(playerUUID)) {
            long lastTime = lastHasteTime.get(playerUUID);

            if ((currentTime - lastTime) < cooldownTime)
                return;
        }


        PotionEffect effect = new PotionEffect(PotionEffectType.FAST_DIGGING, 200, level);
        player.addPotionEffect(effect);

        lastHasteTime.replace(playerUUID, currentTime);
    }

    private void addAmountToMaps(Player player, double money, double essence, double experience) {
        double newMoney = moneyMap.get(player) + money;
        double newEssence = essenceMap.get(player) + essence;
        double newExperience = experienceMap.get(player) + experience;

        moneyMap.replace(player, newMoney);
        essenceMap.replace(player, newEssence);
        experienceMap.replace(player, newExperience);
    }

    public void procCustomEnchants(Player player, ItemStack itemStack) {
        for (CustomEnchant enchant : enchantsManager.getPickaxeEnchants()) {
            if (enchant == null)
                return;
            if (!enchantsManager.hasEnchantment(itemStack, enchant))
                return;
            //Player has enchant

            int level = enchantsManager.getEnchantmentLevel(itemStack, enchant);
            double chance = enchant.getChancePerLevel() * level;

            RandomSystem randomSystem = new RandomSystem();

            if (!randomSystem.success(chance, true))
                return;
            //Command has been activated.

            HashMap<String, Double> commands = enchant.getCommands().getCommands();

            for (String command : commands.keySet()) {
                double commandChance = commands.get(command);
                if (!randomSystem.success(commandChance, true))
                    continue;
                //Give command
                String finalCommand = command.replace("%player%", player.getName());
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    finalCommand = PlaceholderAPI.setPlaceholders(player, finalCommand);
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);

            }
        }
    }
}
