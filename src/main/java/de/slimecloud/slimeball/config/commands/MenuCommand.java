package de.slimecloud.slimeball.config.commands;

import de.mineking.discordutils.commands.ApplicationCommand;
import de.mineking.discordutils.commands.ApplicationCommandMethod;
import de.mineking.discordutils.ui.MessageMenu;
import de.mineking.discordutils.ui.MessageRenderer;
import de.mineking.discordutils.ui.UIManager;
import de.mineking.discordutils.ui.components.button.ButtonColor;
import de.mineking.discordutils.ui.components.button.ButtonComponent;
import de.mineking.discordutils.ui.components.button.MenuComponent;
import de.mineking.discordutils.ui.components.select.EntitySelectComponent;
import de.mineking.discordutils.ui.components.select.StringSelectComponent;
import de.mineking.discordutils.ui.components.types.Component;
import de.mineking.discordutils.ui.components.types.ComponentRow;
import de.mineking.discordutils.ui.state.DataState;
import de.slimecloud.slimeball.config.GuildConfig;
import de.slimecloud.slimeball.config.engine.CategoryInfo;
import de.slimecloud.slimeball.config.engine.ConfigField;
import de.slimecloud.slimeball.config.engine.ConfigFieldType;
import de.slimecloud.slimeball.config.engine.KeyType;
import de.slimecloud.slimeball.main.Main;
import de.slimecloud.slimeball.main.SlimeBot;
import de.slimecloud.slimeball.util.ErrorConsumer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationCommand(name = "menu", description = "Öffnet ein Menü für die Konfiguration")
public class MenuCommand {
	private final MessageMenu menu;

	public MenuCommand(@NotNull SlimeBot bot, @NotNull UIManager manager) {
		List<Component<?>> components = new ArrayList<>(Arrays.stream(GuildConfig.class.getDeclaredFields())
				.filter(f -> f.isAnnotationPresent(CategoryInfo.class))
				.map(f -> {
					CategoryInfo info = f.getAnnotation(CategoryInfo.class);
					f.setAccessible(true);

					return new MenuComponent<>(createCategory(bot, manager, c -> {
						try {
							return f.get(c);
						} catch (IllegalAccessException e) {
							throw new RuntimeException(e);
						}
					}, info, f.getType().getDeclaredFields()), ButtonColor.GRAY, info.name()).asDisabled(s -> {
						try {
							return f.get(bot.loadGuild(s.event.getGuild())) == null;
						} catch (IllegalAccessException e) {
							throw new RuntimeException(e);
						}
					});
				})
				.toList()
		);

		components.add(0, new MenuComponent<>(createCategory(bot, manager, c -> c, GuildConfig.class.getAnnotation(CategoryInfo.class), GuildConfig.class.getDeclaredFields()), ButtonColor.BLUE, "Allgemein"));

		menu = manager.createMenu(
				"config",
				MessageRenderer.embed(s -> new EmbedBuilder()
						.setDescription("## Konfiguration für **" + s.event.getGuild().getName() + "**\n")
						.setColor(bot.getColor(s.event.getGuild()))
						.setThumbnail(s.event.getGuild().getIconUrl())
						.appendDescription("Verwende die Buttons unter dieser Nachricht, um einzelne Kategorien zu konfigurieren\n")
						.appendDescription("Bevor die Konfiguration hier angepasst werden kann, muss eine Kategorie mit `/config <category> enable` aktiviert werden")
						.build()
				),
				ComponentRow.ofMany(components)
		);
	}

	@NotNull
	private static MessageMenu createCategory(@NotNull SlimeBot bot, @NotNull UIManager manager, @NotNull Function<GuildConfig, Object> instance, @NotNull CategoryInfo category, @NotNull Field[] fields) {
		//Get field components
		List<ComponentRow> components = ComponentRow.ofMany(Arrays.stream(fields)
				.filter(f -> f.isAnnotationPresent(ConfigField.class))
				.map(f -> new MenuComponent<>(
						createFieldMenu(bot, manager, instance, category, f),
						ButtonColor.BLUE,
						f.getAnnotation(ConfigField.class).name()
				))
				.toList()
		);

		//Add button to go back to main menu
		components.add(new ButtonComponent("back", ButtonColor.GRAY, "Zurück").appendHandler(s -> manager.getMenu("config").display(s.event)));

		//Create menu
		return manager.createMenu(
				"config." + category.command(),
				MessageRenderer.embed(s -> new EmbedBuilder()
						.setDescription("## " + category.name() + "\n")
						.setColor(bot.getColor(s.event.getGuild()))
						.appendDescription(category.description())
						.appendDescription("\n### Aktuelle Konfiguration\n")
						.appendDescription("```json\n" + Main.formattedJson.toJson(instance.apply(bot.loadGuild(s.event.getGuild()))) + "```")
						.build()
				),
				components
		);
	}

	@NotNull
	private static MessageMenu createFieldMenu(@NotNull SlimeBot bot, @NotNull UIManager manager, @NotNull Function<GuildConfig, Object> instance, CategoryInfo category, @NotNull Field field) {
		ConfigField info = field.getAnnotation(ConfigField.class);

		if (EnumSet.class.isAssignableFrom(field.getType())) return createEnumSetValueMenu(bot, manager, instance, category, info, field);
		if (Collection.class.isAssignableFrom(field.getType())) return createListValueMenu(bot, manager, instance, category, info, field);
		if (Map.class.isAssignableFrom(field.getType())) return createMapValueMenu(bot, manager, instance, category, info, field);
		return createValueMenu(bot, manager, instance, category, info, field);
	}

	private static void handle(@NotNull SlimeBot bot, @NotNull DataState<?> state, @NotNull Function<GuildConfig, Object> instance, @NotNull ErrorConsumer<Object> handler) {
		GuildConfig config = bot.loadGuild(state.event.getGuild());
		try {
			handler.accept(instance.apply(config));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		config.save();
	}

	@SuppressWarnings("unchecked")
	private static <T> T getValue(@NotNull SlimeBot bot, @NotNull DataState<?> state, @NotNull Function<GuildConfig, Object> instance, @NotNull Field field) {
		try {
			return (T) field.get(instance.apply(bot.loadGuild(state.event.getGuild())));
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	@NotNull
	private static MessageMenu createValueMenu(@NotNull SlimeBot bot, @NotNull UIManager manager, @NotNull Function<GuildConfig, Object> instance, @NotNull CategoryInfo category, @NotNull ConfigField info, @NotNull Field field) {
		field.setAccessible(true);

		List<Component<?>> components = new ArrayList<>();

		components.add(new ButtonComponent("back", ButtonColor.GRAY, "Zurück").appendHandler(s -> manager.getMenu("config." + category.command()).display(s.event)));
		components.add(new ButtonComponent("reset", ButtonColor.RED, "Zurücksetzten").appendHandler(s -> {
			handle(bot, s, instance, o -> field.set(o, null));
			s.update();
		}));

		Component<?> component = info.type().createComponent(manager, field.getType(), "config." + category.command() + "." + info.command(), "value", "Wert festlegen", (s, v) -> handle(bot, s, instance, o -> field.set(o, v)));

		if (component instanceof EntitySelectComponent || component instanceof StringSelectComponent) components.add(0, component);
		else components.add(component);

		return manager.createMenu(
				"config." + category.command() + "." + info.command(),
				MessageRenderer.embed(s -> {
					Object value = getValue(bot, s, instance, field);

					return new EmbedBuilder()
							.setDescription("## " + category.name() + " → " + info.name() + "\n")
							.setColor(bot.getColor(s.event.getGuild()))
							.appendDescription(info.description())
							.appendDescription("\n### Aktueller Wert\n")
							.appendDescription(value == null ? "*nicht gesetzt*" : info.type().toString(value))
							.build();
				}),
				ComponentRow.ofMany(components)
		);
	}

	@NotNull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static MessageMenu createListValueMenu(@NotNull SlimeBot bot, @NotNull UIManager manager, @NotNull Function<GuildConfig, Object> instance, @NotNull CategoryInfo category, @NotNull ConfigField info, @NotNull Field field) {
		field.setAccessible(true);

		List<Component<?>> components = new ArrayList<>();

		components.add(new ButtonComponent("back", ButtonColor.GRAY, "Zurück").appendHandler(s -> manager.getMenu("config." + category.command()).display(s.event)));
		components.add(new ButtonComponent("reset", ButtonColor.RED, "Zurücksetzten").appendHandler(s -> {
			handle(bot, s, instance, o -> field.set(o, field.getType().isAssignableFrom(ArrayList.class) ? new ArrayList<>() : new HashSet<>()));
			s.update();
		}));

		Component<?> add = info.type().createComponent(manager, field.getType(), "config." + category.command() + "." + info.command(), "add", "Wert hinzufügen", (s, v) -> handle(bot, s, instance, o -> ((Collection) field.get(o)).add(v)));

		Component<?> remove = new StringSelectComponent("remove", s -> MenuCommand.<Collection<?>>getValue(bot, s, instance, field).stream()
				.map(e -> info.type().createSelectOption(bot, e))
				.toList()
		).setPlaceholder("Wert entfernen").appendHandler((s, v) -> {
			handle(bot, s, instance, o -> ((Collection<?>) field.get(o)).remove(info.type().parse(field.getType(), v.get(0).getValue())));
			s.update();
		});

		if (add instanceof EntitySelectComponent || add instanceof StringSelectComponent) {
			components.add(0, add);
			components.add(1, remove);
		} else {
			components.add(add);
			components.add(remove);
		}

		return manager.createMenu(
				"config." + category.command() + "." + info.command(),
				MessageRenderer.embed(s -> {
					Collection<?> value = getValue(bot, s, instance, field);

					return new EmbedBuilder()
							.setDescription("## " + info.name() + "\n")
							.setColor(bot.getColor(s.event.getGuild()))
							.appendDescription(info.description())
							.appendDescription("\n### Aktuelle Einträge\n")
							.appendDescription(value.isEmpty() ? "*Keine Einträge*" : value.stream().map(e -> "- " + info.type().toString(e)).collect(Collectors.joining("\n")))
							.build();
				}),
				ComponentRow.ofMany(components)
		);
	}

	@SuppressWarnings("unchecked")
	private static <E extends Enum<E>> EnumSet<E> emptyEnumSet(@NotNull Class<?> type) {
		return EnumSet.noneOf((Class<E>) type);
	}

	@NotNull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static MessageMenu createEnumSetValueMenu(@NotNull SlimeBot bot, @NotNull UIManager manager, @NotNull Function<GuildConfig, Object> instance, @NotNull CategoryInfo category, @NotNull ConfigField info, @NotNull Field field) {
		field.setAccessible(true);

		Class<?> enumType = (Class<?>) ((ParameterizedType) field.getType().getGenericSuperclass()).getActualTypeArguments()[0];

		List<Component<?>> components = new ArrayList<>();

		components.add(new ButtonComponent("back", ButtonColor.GRAY, "Zurück").appendHandler(s -> manager.getMenu("config." + category.command()).display(s.event)));
		components.add(new ButtonComponent("reset", ButtonColor.RED, "Zurücksetzten").appendHandler(s -> {
			handle(bot, s, instance, o -> field.set(o, emptyEnumSet(enumType)));
			s.update();
		}));

		components.add(new StringSelectComponent("value", s -> Arrays.stream(enumType.getEnumConstants())
				.map(e -> SelectOption.of(e.toString(), ((Enum<?>) e).name())
						.withDefault(s.<EnumSet<?>>getCache("value").contains(e))
				)
				.toList()
		).setMinValues(0).setMaxValues(enumType.getEnumConstants().length).appendHandler((s, v) -> {
			handle(bot, s, instance, o -> {
				EnumSet value = (EnumSet) field.get(o);
				value.clear();
				value.addAll(v.stream().map(x -> Arrays.stream(enumType.getEnumConstants()).map(e -> (Enum<?>) e).filter(e -> e.name().equals(x.getValue())).findFirst().orElseThrow()).toList());
			});
			s.update();
		}));

		return manager.createMenu(
				"config." + category.command() + "." + info.command(),
				MessageRenderer.embed(s -> {
					EnumSet<?> value = s.getCache("value");

					return new EmbedBuilder()
							.setDescription("## " + info.name() + "\n")
							.setColor(bot.getColor(s.event.getGuild()))
							.appendDescription(info.description())
							.appendDescription("\n### Aktuelle Einträge\n")
							.appendDescription(value.isEmpty() ? "*Keine Einträge*" : value.stream().map(e -> "- " + info.type().toString(e)).collect(Collectors.joining("\n")))
							.build();
				}),
				ComponentRow.ofMany(components)
		).cache(s -> s.setCache("value", getValue(bot, s, instance, field)));
	}

	@NotNull
	private static MessageMenu createMapValueMenu(@NotNull SlimeBot bot, @NotNull UIManager manager, @NotNull Function<GuildConfig, Object> instance, @NotNull CategoryInfo category, @NotNull ConfigField info, @NotNull Field field) {
		field.setAccessible(true);

		ConfigFieldType keyType = field.getAnnotation(KeyType.class).value();

		List<Component<?>> components = new ArrayList<>();

		components.add(new ButtonComponent("back", ButtonColor.GRAY, "Zurück").appendHandler(s -> manager.getMenu("config." + category.command()).display(s.event)));
		components.add(new ButtonComponent("reset", ButtonColor.RED, "Zurücksetzten").appendHandler(s -> {
			handle(bot, s, instance, o -> field.set(o, field.getType().isAssignableFrom(HashMap.class) ? new HashMap<>() : new LinkedHashMap<>()));
			s.update();
		}));

		Component<?> add = info.type().createComponent(manager, field.getType(), "config." + category.command() + "." + info.command(), "add", "Wert hinzufügen", (s, v) -> handle(bot, s, instance, o -> ((Collection) field.get(o)).add(v)));

		Component<?> remove = new StringSelectComponent("remove", s -> MenuCommand.<Map<?, ?>>getValue(bot, s, instance, field).keySet().stream()
				.map(e -> keyType.createSelectOption(bot, e))
				.toList()
		).setPlaceholder("Wert entfernen").appendHandler((s, v) -> {
			handle(bot, s, instance, o -> ((Map<?, ?>) field.get(o)).remove(keyType.parse(field.getType(), v.get(0).getValue())));
			s.update();
		});

		if (add instanceof EntitySelectComponent || add instanceof StringSelectComponent) {
			components.add(0, add);
			components.add(1, remove);
		} else {
			components.add(add);
			components.add(remove);
		}

		return manager.createMenu(
				"config." + category.command() + "." + info.command(),
				MessageRenderer.embed(s -> {
					Map<?, ?> value = getValue(bot, s, instance, field);

					return new EmbedBuilder()
							.setDescription("## " + info.name() + "\n")
							.setColor(bot.getColor(s.event.getGuild()))
							.appendDescription(info.description())
							.appendDescription("\n### Aktuelle Einträge\n")
							.appendDescription(value.isEmpty() ? "*Keine Einträge*" : value.entrySet().stream().map(e -> "- " + keyType.toString(e.getKey()) + " = " + info.type().toString(e.getValue())).collect(Collectors.joining("\n")))
							.build();
				}),
				ComponentRow.ofMany(components)
		);
	}

	@ApplicationCommandMethod
	public void performCommand(@NotNull SlashCommandInteractionEvent event) {
		menu.display(event);
	}
}
