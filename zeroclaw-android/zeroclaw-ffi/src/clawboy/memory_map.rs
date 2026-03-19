// Copyright (c) 2026 @Natfii. All rights reserved.

//! Pokemon Red (USA/Europe) memory map constants and state parser.
//!
//! Reads specific RAM addresses from the Boytacean emulator and converts
//! raw bytes into a structured [`GameState`] for LLM decision-making.
//! All address constants are from the verified No-Intro Pokemon Red dump.

#[allow(dead_code)]
use super::types::{BagItem, GameState, PartyMember, SpecialUiState};

// ── ROM verification ─────────────────────────────────────────────────

/// Expected SHA-1 for Pokemon Red (USA/Europe) No-Intro verified dump.
pub const EXPECTED_SHA1: &str = "EA9BCAE617FDF159B045185467AE58B2E4A48B9A";

// ── RAM addresses ────────────────────────────────────────────────────

/// Player Y coordinate.
#[allow(dead_code)]
const PLAYER_Y: u16 = 0xD361;
/// Player X coordinate.
#[allow(dead_code)]
const PLAYER_X: u16 = 0xD362;
/// Current map bank ID.
#[allow(dead_code)]
const MAP_ID: u16 = 0xD35E;

/// Number of Pokemon in the party (0-6).
#[allow(dead_code)]
const PARTY_COUNT: u16 = 0xD163;
/// Start of the six species-ID bytes (one per party slot).
#[allow(dead_code)]
const PARTY_SPECIES_START: u16 = 0xD164;
/// Start of per-Pokemon data structs (44 bytes each).
#[allow(dead_code)]
const PARTY_DATA_START: u16 = 0xD16B;
/// Size of one party Pokemon data struct in bytes.
#[allow(dead_code)]
const PARTY_STRUCT_SIZE: u16 = 44;

/// Offset within a party struct to the current level byte.
#[allow(dead_code)]
const PARTY_LEVEL_OFFSET: u16 = 33;
/// Offset within a party struct to the current HP (2 bytes, big-endian).
#[allow(dead_code)]
const PARTY_HP_OFFSET: u16 = 1;
/// Offset within a party struct to the max HP (2 bytes, big-endian).
#[allow(dead_code)]
const PARTY_MAX_HP_OFFSET: u16 = 34;
/// Offset within a party struct to the status condition byte.
#[allow(dead_code)]
const PARTY_STATUS_OFFSET: u16 = 4;

/// Battle type flag (nonzero = in battle).
#[allow(dead_code)]
const BATTLE_TYPE: u16 = 0xD057;

/// Badge bitfield (each bit = one badge).
#[allow(dead_code)]
const BADGE_FLAGS: u16 = 0xD356;

/// Start of money (3 BCD-encoded bytes).
#[allow(dead_code)]
const MONEY_START: u16 = 0xD347;

/// Number of distinct items in the bag.
#[allow(dead_code)]
const BAG_COUNT: u16 = 0xD31D;
/// Start of bag item pairs (item_id, count).
#[allow(dead_code)]
const BAG_ITEMS_START: u16 = 0xD31E;

/// `wJoyIgnore` — nonzero means a text box or menu is capturing input.
///
/// Address `0xCD6B` in WRAM (from pret/pokered disassembly).
/// Previously this was incorrectly set to `0xFF8C` (`hJoyInput`
/// in HRAM), which reads the raw joypad register — almost always
/// nonzero — causing `detect_special_ui` to permanently return
/// `TextBox`.
#[allow(dead_code)]
const JOY_IGNORE: u16 = 0xCD6B;
/// Current menu cursor position.
#[allow(dead_code)]
const MENU_CURSOR: u16 = 0xCC26;

/// Ordered badge names matching the bitfield in [`BADGE_FLAGS`].
#[allow(dead_code)]
const BADGE_NAMES: [&str; 8] = [
    "BOULDER", "CASCADE", "THUNDER", "RAINBOW", "SOUL", "MARSH", "VOLCANO", "EARTH",
];

/// Maximum number of party slots the game supports.
#[allow(dead_code)]
const MAX_PARTY_SIZE: u8 = 6;

/// Maximum number of distinct bag item slots.
#[allow(dead_code)]
const MAX_BAG_ITEMS: u8 = 20;

// ── Public API ───────────────────────────────────────────────────────

/// Reads the current Pokemon Red game state from emulator memory.
///
/// Uses a closure-based memory access pattern so this module does not
/// depend on the emulator implementation directly. The closure `read_byte`
/// takes a 16-bit address and returns the byte at that address.
///
/// # Examples
///
/// ```ignore
/// let state = read_game_state(|addr| emulator.read(addr));
/// println!("Player is at {} ({}, {})", state.map_name, state.player_x, state.player_y);
/// ```
#[allow(dead_code)]
pub fn read_game_state<F>(mut read_byte: F) -> GameState
where
    F: FnMut(u16) -> u8,
{
    let map_id = read_byte(MAP_ID);
    let badge_byte = read_byte(BADGE_FLAGS);
    let (badges, badge_count) = decode_badges(badge_byte);
    let battle_type = read_byte(BATTLE_TYPE);

    GameState {
        map_id,
        map_name: map_id_to_name(map_id).to_owned(),
        player_x: read_byte(PLAYER_X),
        player_y: read_byte(PLAYER_Y),
        in_battle: battle_type != 0,
        party: read_party(&mut read_byte),
        badges,
        badge_count,
        money: read_bcd_money(&mut read_byte),
        bag: read_bag(&mut read_byte),
        special_ui: detect_special_ui(&mut read_byte),
    }
}

// ── Private helpers ──────────────────────────────────────────────────

/// Reads the player's party from memory.
///
/// Reads `PARTY_COUNT` (clamped to 6), then for each slot reads the
/// species ID from the species list and level/HP/status from the
/// per-Pokemon data struct.
#[allow(dead_code)]
fn read_party<F>(read_byte: &mut F) -> Vec<PartyMember>
where
    F: FnMut(u16) -> u8,
{
    let count = read_byte(PARTY_COUNT).min(MAX_PARTY_SIZE);
    let mut party = Vec::with_capacity(count as usize);

    for i in 0..u16::from(count) {
        let species_id = read_byte(PARTY_SPECIES_START + i);
        let struct_base = PARTY_DATA_START + i * PARTY_STRUCT_SIZE;

        let hp_hi = read_byte(struct_base + PARTY_HP_OFFSET);
        let hp_lo = read_byte(struct_base + PARTY_HP_OFFSET + 1);
        let max_hp_hi = read_byte(struct_base + PARTY_MAX_HP_OFFSET);
        let max_hp_lo = read_byte(struct_base + PARTY_MAX_HP_OFFSET + 1);

        party.push(PartyMember {
            species: species_id_to_name(species_id).to_owned(),
            level: read_byte(struct_base + PARTY_LEVEL_OFFSET),
            hp: u16::from(hp_hi) << 8 | u16::from(hp_lo),
            max_hp: u16::from(max_hp_hi) << 8 | u16::from(max_hp_lo),
            status: status_byte_to_name(read_byte(struct_base + PARTY_STATUS_OFFSET)).to_owned(),
        });
    }

    party
}

/// Decodes 3 BCD-encoded bytes at [`MONEY_START`] into a `u32`.
///
/// Each byte holds two decimal digits: `0x12 0x34 0x56` => 123456.
#[allow(dead_code)]
fn read_bcd_money<F>(read_byte: &mut F) -> u32
where
    F: FnMut(u16) -> u8,
{
    let mut total: u32 = 0;
    for offset in 0..3u16 {
        let byte = read_byte(MONEY_START + offset);
        let high = u32::from(byte >> 4);
        let low = u32::from(byte & 0x0F);
        total = total * 100 + high * 10 + low;
    }
    total
}

/// Reads the player's bag items from memory.
///
/// Reads `BAG_COUNT` (clamped to 20), then reads (item_id, count) pairs.
#[allow(dead_code)]
fn read_bag<F>(read_byte: &mut F) -> Vec<BagItem>
where
    F: FnMut(u16) -> u8,
{
    let count = read_byte(BAG_COUNT).min(MAX_BAG_ITEMS);
    let mut items = Vec::with_capacity(count as usize);

    for i in 0..u16::from(count) {
        let base = BAG_ITEMS_START + i * 2;
        let item_id = read_byte(base);
        let qty = read_byte(base + 1);
        items.push(BagItem {
            name: item_id_to_name(item_id).to_owned(),
            count: qty,
        });
    }

    items
}

/// Decodes the badge bitfield into a list of earned badge names and count.
#[allow(dead_code)]
fn decode_badges(byte: u8) -> (Vec<String>, u8) {
    let mut names = Vec::new();
    let mut count: u8 = 0;
    for (i, &name) in BADGE_NAMES.iter().enumerate() {
        if byte & (1 << i) != 0 {
            names.push(name.to_owned());
            count += 1;
        }
    }
    (names, count)
}

/// Determines the current special UI state from memory flags.
///
/// Checks `JOY_IGNORE` (text box active), `BATTLE_TYPE` (battle menus),
/// and `MENU_CURSOR` to disambiguate overlay states.
#[allow(dead_code)]
fn detect_special_ui<F>(read_byte: &mut F) -> SpecialUiState
where
    F: FnMut(u16) -> u8,
{
    let battle = read_byte(BATTLE_TYPE);
    let joy_ignore = read_byte(JOY_IGNORE);

    if battle != 0 {
        return SpecialUiState::BattleMenu;
    }

    if joy_ignore != 0 {
        return SpecialUiState::TextBox;
    }

    let _ = read_byte(MENU_CURSOR);

    SpecialUiState::None
}

// ── Lookup tables ────────────────────────────────────────────────────

/// Maps a Gen 1 map ID byte to a human-readable location name.
#[allow(dead_code, clippy::too_many_lines)]
fn map_id_to_name(id: u8) -> &'static str {
    match id {
        0 => "PALLET_TOWN",
        1 => "VIRIDIAN_CITY",
        2 => "PEWTER_CITY",
        3 => "CERULEAN_CITY",
        4 => "LAVENDER_TOWN",
        5 => "VERMILION_CITY",
        6 => "CELADON_CITY",
        7 => "FUCHSIA_CITY",
        8 => "CINNABAR_ISLAND",
        9 => "INDIGO_PLATEAU",
        10 => "SAFFRON_CITY",
        12 => "ROUTE_1",
        13 => "ROUTE_2",
        14 => "ROUTE_3",
        15 => "ROUTE_4",
        16 => "ROUTE_5",
        17 => "ROUTE_6",
        18 => "ROUTE_7",
        19 => "ROUTE_8",
        20 => "ROUTE_9",
        21 => "ROUTE_10",
        22 => "ROUTE_11",
        23 => "ROUTE_12",
        24 => "ROUTE_13",
        25 => "ROUTE_14",
        26 => "ROUTE_15",
        27 => "ROUTE_16",
        28 => "ROUTE_17",
        29 => "ROUTE_18",
        30 => "ROUTE_19",
        31 => "ROUTE_20",
        32 => "ROUTE_21",
        33 => "ROUTE_22",
        34 => "ROUTE_23",
        35 => "ROUTE_24",
        36 => "ROUTE_25",
        37 => "REDS_HOUSE_1F",
        38 => "REDS_HOUSE_2F",
        39 => "BLUES_HOUSE",
        40 => "OAKS_LAB",
        41 => "VIRIDIAN_POKECENTER",
        42 => "VIRIDIAN_MART",
        43 => "VIRIDIAN_SCHOOL",
        44 => "VIRIDIAN_HOUSE",
        45 => "VIRIDIAN_GYM",
        46 => "DIGLETTS_CAVE_ROUTE_2",
        47 => "VIRIDIAN_FOREST_ENTRANCE",
        48 => "VIRIDIAN_FOREST",
        49 => "MUSEUM_1F",
        50 => "MUSEUM_2F",
        51 => "PEWTER_GYM",
        52 => "PEWTER_HOUSE",
        53 => "PEWTER_MART",
        54 => "PEWTER_POKECENTER",
        55 => "MT_MOON_1F",
        56 => "MT_MOON_B1F",
        57 => "MT_MOON_B2F",
        58 => "CERULEAN_TRASHED_HOUSE",
        59 => "CERULEAN_TRADE_HOUSE",
        60 => "CERULEAN_POKECENTER",
        61 => "CERULEAN_GYM",
        62 => "BIKE_SHOP",
        63 => "CERULEAN_MART",
        64 => "MT_MOON_POKECENTER",
        65 => "CERULEAN_HOUSE",
        68 => "VERMILION_POKECENTER",
        69 => "FAN_CLUB",
        70 => "VERMILION_MART",
        71 => "VERMILION_GYM",
        72 => "VERMILION_HOUSE",
        73 => "VERMILION_DOCK",
        74 => "SS_ANNE_1F",
        75 => "SS_ANNE_2F",
        76 => "SS_ANNE_3F",
        77 => "SS_ANNE_B1F",
        78 => "SS_ANNE_DECK",
        79 => "SS_ANNE_KITCHEN",
        80 => "SS_ANNE_CAPTAINS_ROOM",
        81 => "SS_ANNE_1F_ROOMS",
        82 => "SS_ANNE_2F_ROOMS",
        83 => "SS_ANNE_B1F_ROOMS",
        86 => "UNDERGROUND_PATH_NS",
        87 => "UNDERGROUND_PATH_WE",
        88 => "DIGLETTS_CAVE",
        89 => "SILPH_CO_1F",
        90 => "SILPH_CO_2F",
        91 => "SILPH_CO_3F",
        92 => "SILPH_CO_4F",
        93 => "SILPH_CO_5F",
        94 => "SILPH_CO_6F",
        95 => "SILPH_CO_7F",
        96 => "SILPH_CO_8F",
        97 => "SILPH_CO_9F",
        98 => "SILPH_CO_10F",
        99 => "SILPH_CO_11F",
        100 => "CELADON_MANSION_1F",
        101 => "CELADON_MANSION_2F",
        102 => "CELADON_MANSION_3F",
        103 => "CELADON_MANSION_ROOF",
        104 => "CELADON_POKECENTER",
        105 => "CELADON_GYM",
        106 => "GAME_CORNER",
        107 => "CELADON_DEPT_STORE_1F",
        108 => "CELADON_DEPT_STORE_2F",
        109 => "CELADON_DEPT_STORE_3F",
        110 => "CELADON_DEPT_STORE_4F",
        111 => "CELADON_DEPT_STORE_5F",
        112 => "CELADON_DEPT_STORE_ROOF",
        113 => "CELADON_MART",
        114 => "CELADON_DINER",
        115 => "CELADON_HOTEL",
        116 => "LAVENDER_POKECENTER",
        117 => "POKEMON_TOWER_1F",
        118 => "POKEMON_TOWER_2F",
        119 => "POKEMON_TOWER_3F",
        120 => "POKEMON_TOWER_4F",
        121 => "POKEMON_TOWER_5F",
        122 => "POKEMON_TOWER_6F",
        123 => "POKEMON_TOWER_7F",
        124 => "LAVENDER_HOUSE_1",
        125 => "LAVENDER_MART",
        126 => "LAVENDER_HOUSE_2",
        133 => "FUCHSIA_POKECENTER",
        134 => "FUCHSIA_MART",
        135 => "FUCHSIA_HOUSE_1",
        136 => "FUCHSIA_GYM",
        137 => "FUCHSIA_HOUSE_2",
        138 => "SAFARI_ZONE_ENTRANCE",
        139 => "SAFARI_ZONE_CENTER",
        140 => "SAFARI_ZONE_AREA_1",
        141 => "SAFARI_ZONE_AREA_2",
        142 => "SAFARI_ZONE_AREA_3",
        143 => "SAFARI_ZONE_REST_HOUSE_1",
        144 => "SAFARI_ZONE_SECRET_HOUSE",
        145 => "SAFARI_ZONE_REST_HOUSE_2",
        146 => "SAFARI_ZONE_REST_HOUSE_3",
        147 => "SAFARI_ZONE_REST_HOUSE_4",
        149 => "SAFFRON_GYM",
        150 => "SAFFRON_POKECENTER",
        151 => "SAFFRON_MART",
        152 => "SILPH_CO_ENTRANCE",
        153 => "SAFFRON_HOUSE_1",
        154 => "SAFFRON_HOUSE_2",
        155 => "FIGHTING_DOJO",
        156 => "SAFFRON_HOUSE_3",
        158 => "CINNABAR_GYM",
        159 => "CINNABAR_POKECENTER",
        160 => "CINNABAR_MART",
        161 => "CINNABAR_LAB",
        162 => "CINNABAR_LAB_TRADE_ROOM",
        163 => "CINNABAR_LAB_FOSSIL_ROOM",
        164 => "CINNABAR_LAB_METRONOME_ROOM",
        165 => "CINNABAR_POKEMON_CENTER",
        166 => "POKEMON_MANSION_1F",
        167 => "POKEMON_MANSION_2F",
        168 => "POKEMON_MANSION_3F",
        169 => "POKEMON_MANSION_B1F",
        170 => "INDIGO_PLATEAU_LOBBY",
        171 => "LORELEIS_ROOM",
        172 => "BRUNOS_ROOM",
        173 => "AGATHAS_ROOM",
        174 => "LANCES_ROOM",
        175 => "HALL_OF_FAME",
        198 => "POWER_PLANT",
        199 => "VICTORY_ROAD_1F",
        200 => "VICTORY_ROAD_2F",
        201 => "VICTORY_ROAD_3F",
        208 => "ROCK_TUNNEL_1F",
        209 => "ROCK_TUNNEL_B1F",
        210 => "SEAFOAM_ISLANDS_1F",
        211 => "SEAFOAM_ISLANDS_B1F",
        212 => "SEAFOAM_ISLANDS_B2F",
        213 => "SEAFOAM_ISLANDS_B3F",
        214 => "SEAFOAM_ISLANDS_B4F",
        215 => "POKEMON_LEAGUE_GATE",
        217 => "BILLS_HOUSE",
        228 => "UNKNOWN_DUNGEON_1F",
        229 => "UNKNOWN_DUNGEON_2F",
        230 => "UNKNOWN_DUNGEON_B1F",
        233 => "TRADE_CENTER",
        234 => "COLOSSEUM",
        _ => "UNKNOWN_MAP",
    }
}

/// Maps a Gen 1 **internal index** to a species name.
///
/// Pokemon Red uses an internal species index that differs from the
/// Pokedex number. For example, Bulbasaur is internal ID 153, not 1.
/// ID 0 is treated as empty/no Pokemon.
#[allow(dead_code, clippy::too_many_lines, clippy::match_same_arms)]
fn species_id_to_name(id: u8) -> &'static str {
    match id {
        0x00 => "UNKNOWN",
        0x01 => "RHYDON",
        0x02 => "KANGASKHAN",
        0x03 => "NIDORAN_M",
        0x04 => "CLEFAIRY",
        0x05 => "SPEAROW",
        0x06 => "VOLTORB",
        0x07 => "NIDOKING",
        0x08 => "SLOWBRO",
        0x09 => "IVYSAUR",
        0x0A => "EXEGGUTOR",
        0x0B => "LICKITUNG",
        0x0C => "EXEGGCUTE",
        0x0D => "GRIMER",
        0x0E => "GENGAR",
        0x0F => "NIDORAN_F",
        0x10 => "NIDOQUEEN",
        0x11 => "CUBONE",
        0x12 => "RHYHORN",
        0x13 => "LAPRAS",
        0x14 => "ARCANINE",
        0x15 => "MEW",
        0x16 => "GYARADOS",
        0x17 => "SHELLDER",
        0x18 => "TENTACOOL",
        0x19 => "GASTLY",
        0x1A => "SCYTHER",
        0x1B => "STARYU",
        0x1C => "BLASTOISE",
        0x1D => "PINSIR",
        0x1E => "TANGELA",
        0x21 => "GROWLITHE",
        0x22 => "ONIX",
        0x23 => "FEAROW",
        0x24 => "PIDGEY",
        0x25 => "SLOWPOKE",
        0x26 => "KADABRA",
        0x27 => "GRAVELER",
        0x28 => "CHANSEY",
        0x29 => "MACHOKE",
        0x2A => "MR_MIME",
        0x2B => "HITMONLEE",
        0x2C => "HITMONCHAN",
        0x2D => "ARBOK",
        0x2E => "PARASECT",
        0x2F => "PSYDUCK",
        0x30 => "DROWZEE",
        0x31 => "GOLEM",
        0x33 => "MAGMAR",
        0x35 => "ELECTABUZZ",
        0x36 => "MAGNETON",
        0x37 => "KOFFING",
        0x39 => "MANKEY",
        0x3A => "SEEL",
        0x3B => "DIGLETT",
        0x3C => "TAUROS",
        0x40 => "FARFETCHD",
        0x41 => "VENONAT",
        0x42 => "DRAGONITE",
        0x46 => "DODUO",
        0x47 => "POLIWAG",
        0x48 => "JYNX",
        0x49 => "MOLTRES",
        0x4A => "ARTICUNO",
        0x4B => "ZAPDOS",
        0x4C => "DITTO",
        0x4D => "MEOWTH",
        0x4E => "KRABBY",
        0x52 => "VULPIX",
        0x53 => "NINETALES",
        0x54 => "PIKACHU",
        0x55 => "RAICHU",
        0x58 => "DRATINI",
        0x59 => "DRAGONAIR",
        0x5A => "KABUTO",
        0x5B => "KABUTOPS",
        0x5C => "HORSEA",
        0x5D => "SEADRA",
        0x60 => "SANDSHREW",
        0x61 => "SANDSLASH",
        0x62 => "OMANYTE",
        0x63 => "OMASTAR",
        0x64 => "JIGGLYPUFF",
        0x65 => "WIGGLYTUFF",
        0x66 => "EEVEE",
        0x67 => "FLAREON",
        0x68 => "JOLTEON",
        0x69 => "VAPOREON",
        0x6A => "MACHOP",
        0x6B => "ZUBAT",
        0x6C => "EKANS",
        0x6D => "PARAS",
        0x6E => "POLIWHIRL",
        0x6F => "POLIWRATH",
        0x70 => "WEEDLE",
        0x71 => "KAKUNA",
        0x72 => "BEEDRILL",
        0x74 => "DODRIO",
        0x75 => "PRIMEAPE",
        0x76 => "DUGTRIO",
        0x77 => "VENOMOTH",
        0x78 => "DEWGONG",
        0x7B => "CATERPIE",
        0x7C => "METAPOD",
        0x7D => "BUTTERFREE",
        0x7E => "MACHAMP",
        0x80 => "GOLDUCK",
        0x81 => "HYPNO",
        0x82 => "GOLBAT",
        0x83 => "MEWTWO",
        0x84 => "SNORLAX",
        0x85 => "MAGIKARP",
        0x88 => "MUK",
        0x8A => "KINGLER",
        0x8B => "CLOYSTER",
        0x8D => "ELECTRODE",
        0x8E => "CLEFABLE",
        0x8F => "WEEZING",
        0x90 => "PERSIAN",
        0x91 => "MAROWAK",
        0x93 => "HAUNTER",
        0x94 => "ABRA",
        0x95 => "ALAKAZAM",
        0x96 => "PIDGEOTTO",
        0x97 => "PIDGEOT",
        0x98 => "STARMIE",
        0x99 => "BULBASAUR",
        0x9A => "VENUSAUR",
        0x9B => "TENTACRUEL",
        0x9D => "GOLDEEN",
        0x9E => "SEAKING",
        0xA3 => "PONYTA",
        0xA4 => "RAPIDASH",
        0xA5 => "RATTATA",
        0xA6 => "RATICATE",
        0xA7 => "NIDORINO",
        0xA8 => "NIDORINA",
        0xA9 => "GEODUDE",
        0xAA => "PORYGON",
        0xAB => "AERODACTYL",
        0xAD => "MAGNEMITE",
        0xB0 => "CHARMANDER",
        0xB1 => "SQUIRTLE",
        0xB2 => "CHARMELEON",
        0xB3 => "WARTORTLE",
        0xB4 => "CHARIZARD",
        0xB9 => "ODDISH",
        0xBA => "GLOOM",
        0xBB => "VILEPLUME",
        0xBC => "BELLSPROUT",
        0xBD => "WEEPINBELL",
        0xBE => "VICTREEBEL",
        _ => "UNKNOWN",
    }
}

/// Maps a Gen 1 item ID to a human-readable item name.
#[allow(dead_code, clippy::too_many_lines)]
fn item_id_to_name(id: u8) -> &'static str {
    match id {
        0x01 => "MASTER_BALL",
        0x02 => "ULTRA_BALL",
        0x03 => "GREAT_BALL",
        0x04 => "POKE_BALL",
        0x05 => "TOWN_MAP",
        0x06 => "BICYCLE",
        0x07 => "SURFBOARD",
        0x08 => "SAFARI_BALL",
        0x09 => "POKEDEX",
        0x0A => "MOON_STONE",
        0x0B => "ANTIDOTE",
        0x0C => "BURN_HEAL",
        0x0D => "ICE_HEAL",
        0x0E => "AWAKENING",
        0x0F => "PARLYZ_HEAL",
        0x10 => "FULL_RESTORE",
        0x11 => "MAX_POTION",
        0x12 => "HYPER_POTION",
        0x13 => "SUPER_POTION",
        0x14 => "POTION",
        0x15 => "BOULDER_BADGE",
        0x16 => "CASCADE_BADGE",
        0x17 => "THUNDER_BADGE",
        0x18 => "RAINBOW_BADGE",
        0x19 => "SOUL_BADGE",
        0x1A => "MARSH_BADGE",
        0x1B => "VOLCANO_BADGE",
        0x1C => "EARTH_BADGE",
        0x1D => "ESCAPE_ROPE",
        0x1E => "REPEL",
        0x1F => "OLD_AMBER",
        0x20 => "FIRE_STONE",
        0x21 => "THUNDER_STONE",
        0x22 => "WATER_STONE",
        0x23 => "HP_UP",
        0x24 => "PROTEIN",
        0x25 => "IRON",
        0x26 => "CARBOS",
        0x27 => "CALCIUM",
        0x28 => "RARE_CANDY",
        0x29 => "DOME_FOSSIL",
        0x2A => "HELIX_FOSSIL",
        0x2B => "SECRET_KEY",
        0x2D => "BIKE_VOUCHER",
        0x2E => "X_ACCURACY",
        0x2F => "LEAF_STONE",
        0x30 => "CARD_KEY",
        0x31 => "NUGGET",
        0x32 => "PP_UP",
        0x33 => "POKE_DOLL",
        0x34 => "FULL_HEAL",
        0x35 => "REVIVE",
        0x36 => "MAX_REVIVE",
        0x37 => "GUARD_SPEC",
        0x38 => "SUPER_REPEL",
        0x39 => "MAX_REPEL",
        0x3A => "DIRE_HIT",
        0x3B => "COIN",
        0x3C => "FRESH_WATER",
        0x3D => "SODA_POP",
        0x3E => "LEMONADE",
        0x3F => "SS_TICKET",
        0x40 => "GOLD_TEETH",
        0x41 => "X_ATTACK",
        0x42 => "X_DEFEND",
        0x43 => "X_SPEED",
        0x44 => "X_SPECIAL",
        0x45 => "COIN_CASE",
        0x46 => "OAKS_PARCEL",
        0x47 => "ITEMFINDER",
        0x48 => "SILPH_SCOPE",
        0x49 => "POKE_FLUTE",
        0x4A => "LIFT_KEY",
        0x4B => "EXP_ALL",
        0x4C => "OLD_ROD",
        0x4D => "GOOD_ROD",
        0x4E => "SUPER_ROD",
        0x4F => "PP_UP_2",
        0x50 => "ETHER",
        0x51 => "MAX_ETHER",
        0x52 => "ELIXIR",
        0x53 => "MAX_ELIXIR",
        0xC4 => "HM_01",
        0xC5 => "HM_02",
        0xC6 => "HM_03",
        0xC7 => "HM_04",
        0xC8 => "HM_05",
        0xC9 => "TM_01",
        0xCA => "TM_02",
        0xCB => "TM_03",
        0xCC => "TM_04",
        0xCD => "TM_05",
        0xCE => "TM_06",
        0xCF => "TM_07",
        0xD0 => "TM_08",
        0xD1 => "TM_09",
        0xD2 => "TM_10",
        0xD3 => "TM_11",
        0xD4 => "TM_12",
        0xD5 => "TM_13",
        0xD6 => "TM_14",
        0xD7 => "TM_15",
        0xD8 => "TM_16",
        0xD9 => "TM_17",
        0xDA => "TM_18",
        0xDB => "TM_19",
        0xDC => "TM_20",
        0xDD => "TM_21",
        0xDE => "TM_22",
        0xDF => "TM_23",
        0xE0 => "TM_24",
        0xE1 => "TM_25",
        0xE2 => "TM_26",
        0xE3 => "TM_27",
        0xE4 => "TM_28",
        0xE5 => "TM_29",
        0xE6 => "TM_30",
        0xE7 => "TM_31",
        0xE8 => "TM_32",
        0xE9 => "TM_33",
        0xEA => "TM_34",
        0xEB => "TM_35",
        0xEC => "TM_36",
        0xED => "TM_37",
        0xEE => "TM_38",
        0xEF => "TM_39",
        0xF0 => "TM_40",
        0xF1 => "TM_41",
        0xF2 => "TM_42",
        0xF3 => "TM_43",
        0xF4 => "TM_44",
        0xF5 => "TM_45",
        0xF6 => "TM_46",
        0xF7 => "TM_47",
        0xF8 => "TM_48",
        0xF9 => "TM_49",
        0xFA => "TM_50",
        _ => "UNKNOWN_ITEM",
    }
}

/// Converts a status condition byte to a human-readable name.
///
/// The lower 3 bits encode sleep turns (1-7 = sleeping), and bits 3-6
/// encode poison, burn, freeze, and paralysis respectively.
#[allow(dead_code)]
fn status_byte_to_name(byte: u8) -> &'static str {
    if byte == 0 {
        return "OK";
    }
    // Sleep uses the lower 3 bits as a turn counter (1-7).
    if byte & 0x07 != 0 {
        return "SLP";
    }
    if byte & 0x08 != 0 {
        return "PSN";
    }
    if byte & 0x10 != 0 {
        return "BRN";
    }
    if byte & 0x20 != 0 {
        return "FRZ";
    }
    if byte & 0x40 != 0 {
        return "PAR";
    }
    "OK"
}

// ── Tests ────────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    /// Helper: builds a flat memory map and returns a read closure.
    fn make_memory(entries: &[(u16, u8)]) -> impl Fn(u16) -> u8 + use<> {
        let map: HashMap<u16, u8> = entries.iter().copied().collect();
        move |addr| map.get(&addr).copied().unwrap_or(0)
    }

    #[test]
    fn bcd_money_decodes_correctly() {
        let mut mem = make_memory(&[
            (MONEY_START, 0x01),
            (MONEY_START + 1, 0x23),
            (MONEY_START + 2, 0x45),
        ]);
        assert_eq!(read_bcd_money(&mut mem), 12345);
    }

    #[test]
    fn bcd_money_zero() {
        let mut mem = make_memory(&[]);
        assert_eq!(read_bcd_money(&mut mem), 0);
    }

    #[test]
    fn bcd_money_max() {
        let mut mem = make_memory(&[
            (MONEY_START, 0x99),
            (MONEY_START + 1, 0x99),
            (MONEY_START + 2, 0x99),
        ]);
        assert_eq!(read_bcd_money(&mut mem), 999_999);
    }

    #[test]
    fn decode_badges_none() {
        let (names, count) = decode_badges(0x00);
        assert!(names.is_empty());
        assert_eq!(count, 0);
    }

    #[test]
    fn decode_badges_all() {
        let (names, count) = decode_badges(0xFF);
        assert_eq!(count, 8);
        assert_eq!(
            names,
            vec![
                "BOULDER", "CASCADE", "THUNDER", "RAINBOW", "SOUL", "MARSH", "VOLCANO", "EARTH"
            ]
        );
    }

    #[test]
    fn decode_badges_partial() {
        // Bits 0 and 2: BOULDER and THUNDER
        let (names, count) = decode_badges(0b0000_0101);
        assert_eq!(count, 2);
        assert_eq!(names, vec!["BOULDER", "THUNDER"]);
    }

    #[test]
    fn status_byte_ok() {
        assert_eq!(status_byte_to_name(0), "OK");
    }

    #[test]
    fn status_byte_sleep() {
        assert_eq!(status_byte_to_name(0x03), "SLP");
    }

    #[test]
    fn status_byte_poison() {
        assert_eq!(status_byte_to_name(0x08), "PSN");
    }

    #[test]
    fn status_byte_burn() {
        assert_eq!(status_byte_to_name(0x10), "BRN");
    }

    #[test]
    fn status_byte_freeze() {
        assert_eq!(status_byte_to_name(0x20), "FRZ");
    }

    #[test]
    fn status_byte_paralysis() {
        assert_eq!(status_byte_to_name(0x40), "PAR");
    }

    #[test]
    fn species_lookup_starters() {
        assert_eq!(species_id_to_name(0x99), "BULBASAUR");
        assert_eq!(species_id_to_name(0xB0), "CHARMANDER");
        assert_eq!(species_id_to_name(0xB1), "SQUIRTLE");
    }

    #[test]
    fn species_lookup_pikachu() {
        assert_eq!(species_id_to_name(0x54), "PIKACHU");
    }

    #[test]
    fn species_lookup_evolved_starters() {
        assert_eq!(species_id_to_name(0x09), "IVYSAUR");
        assert_eq!(species_id_to_name(0x9A), "VENUSAUR");
        assert_eq!(species_id_to_name(0xB2), "CHARMELEON");
        assert_eq!(species_id_to_name(0xB4), "CHARIZARD");
        assert_eq!(species_id_to_name(0xB3), "WARTORTLE");
        assert_eq!(species_id_to_name(0x1C), "BLASTOISE");
    }

    #[test]
    fn species_lookup_unknown() {
        assert_eq!(species_id_to_name(0x00), "UNKNOWN");
        assert_eq!(species_id_to_name(0xFF), "UNKNOWN");
    }

    #[test]
    fn species_lookup_legendaries() {
        assert_eq!(species_id_to_name(0x49), "MOLTRES");
        assert_eq!(species_id_to_name(0x4A), "ARTICUNO");
        assert_eq!(species_id_to_name(0x4B), "ZAPDOS");
        assert_eq!(species_id_to_name(0x83), "MEWTWO");
        assert_eq!(species_id_to_name(0x15), "MEW");
    }

    #[test]
    fn item_lookup_balls() {
        assert_eq!(item_id_to_name(0x01), "MASTER_BALL");
        assert_eq!(item_id_to_name(0x02), "ULTRA_BALL");
        assert_eq!(item_id_to_name(0x03), "GREAT_BALL");
        assert_eq!(item_id_to_name(0x04), "POKE_BALL");
    }

    #[test]
    fn item_lookup_potions() {
        assert_eq!(item_id_to_name(0x14), "POTION");
        assert_eq!(item_id_to_name(0x13), "SUPER_POTION");
        assert_eq!(item_id_to_name(0x12), "HYPER_POTION");
        assert_eq!(item_id_to_name(0x11), "MAX_POTION");
        assert_eq!(item_id_to_name(0x10), "FULL_RESTORE");
    }

    #[test]
    fn item_lookup_unknown() {
        assert_eq!(item_id_to_name(0x00), "UNKNOWN_ITEM");
        assert_eq!(item_id_to_name(0xFF), "UNKNOWN_ITEM");
    }

    #[test]
    fn map_lookup_towns() {
        assert_eq!(map_id_to_name(0), "PALLET_TOWN");
        assert_eq!(map_id_to_name(1), "VIRIDIAN_CITY");
        assert_eq!(map_id_to_name(2), "PEWTER_CITY");
        assert_eq!(map_id_to_name(10), "SAFFRON_CITY");
    }

    #[test]
    fn map_lookup_unknown() {
        assert_eq!(map_id_to_name(255), "UNKNOWN_MAP");
    }

    #[test]
    fn read_party_empty() {
        let mut mem = make_memory(&[]);
        let party = read_party(&mut mem);
        assert!(party.is_empty());
    }

    #[test]
    fn read_party_one_pokemon() {
        let struct_base = PARTY_DATA_START;
        let mut mem = make_memory(&[
            (PARTY_COUNT, 1),
            (PARTY_SPECIES_START, 0x99), // Bulbasaur
            // Level
            (struct_base + PARTY_LEVEL_OFFSET, 15),
            // Current HP: 45 (0x002D)
            (struct_base + PARTY_HP_OFFSET, 0x00),
            (struct_base + PARTY_HP_OFFSET + 1, 0x2D),
            // Max HP: 48 (0x0030)
            (struct_base + PARTY_MAX_HP_OFFSET, 0x00),
            (struct_base + PARTY_MAX_HP_OFFSET + 1, 0x30),
            // Status: OK
            (struct_base + PARTY_STATUS_OFFSET, 0x00),
        ]);
        let party = read_party(&mut mem);
        assert_eq!(party.len(), 1);
        assert_eq!(party[0].species, "BULBASAUR");
        assert_eq!(party[0].level, 15);
        assert_eq!(party[0].hp, 45);
        assert_eq!(party[0].max_hp, 48);
        assert_eq!(party[0].status, "OK");
    }

    #[test]
    fn read_party_clamps_to_six() {
        let mut mem = make_memory(&[(PARTY_COUNT, 99)]);
        let party = read_party(&mut mem);
        assert_eq!(party.len(), 6);
    }

    #[test]
    fn read_bag_empty() {
        let mut mem = make_memory(&[]);
        let bag = read_bag(&mut mem);
        assert!(bag.is_empty());
    }

    #[test]
    fn read_bag_two_items() {
        let mut mem = make_memory(&[
            (BAG_COUNT, 2),
            (BAG_ITEMS_START, 0x04),     // POKE_BALL
            (BAG_ITEMS_START + 1, 5),    // qty 5
            (BAG_ITEMS_START + 2, 0x14), // POTION
            (BAG_ITEMS_START + 3, 3),    // qty 3
        ]);
        let bag = read_bag(&mut mem);
        assert_eq!(bag.len(), 2);
        assert_eq!(bag[0].name, "POKE_BALL");
        assert_eq!(bag[0].count, 5);
        assert_eq!(bag[1].name, "POTION");
        assert_eq!(bag[1].count, 3);
    }

    #[test]
    fn read_bag_clamps_to_twenty() {
        let mut mem = make_memory(&[(BAG_COUNT, 50)]);
        let bag = read_bag(&mut mem);
        assert_eq!(bag.len(), 20);
    }

    #[test]
    fn detect_special_ui_battle() {
        let mut mem = make_memory(&[(BATTLE_TYPE, 1)]);
        assert!(matches!(
            detect_special_ui(&mut mem),
            SpecialUiState::BattleMenu
        ));
    }

    #[test]
    fn detect_special_ui_textbox() {
        let mut mem = make_memory(&[(JOY_IGNORE, 0xFF)]);
        assert!(matches!(
            detect_special_ui(&mut mem),
            SpecialUiState::TextBox
        ));
    }

    #[test]
    fn detect_special_ui_none() {
        let mut mem = make_memory(&[]);
        assert!(matches!(detect_special_ui(&mut mem), SpecialUiState::None));
    }

    #[test]
    fn full_game_state_integration() {
        let struct_base = PARTY_DATA_START;
        let mut mem = make_memory(&[
            (MAP_ID, 2), // PEWTER_CITY
            (PLAYER_X, 10),
            (PLAYER_Y, 5),
            (BADGE_FLAGS, 0x01), // BOULDER badge
            (MONEY_START, 0x00),
            (MONEY_START + 1, 0x34),
            (MONEY_START + 2, 0x56),
            (PARTY_COUNT, 1),
            (PARTY_SPECIES_START, 0xB0), // Charmander
            (struct_base + PARTY_LEVEL_OFFSET, 14),
            (struct_base + PARTY_HP_OFFSET, 0x00),
            (struct_base + PARTY_HP_OFFSET + 1, 0x28),
            (struct_base + PARTY_MAX_HP_OFFSET, 0x00),
            (struct_base + PARTY_MAX_HP_OFFSET + 1, 0x2A),
            (struct_base + PARTY_STATUS_OFFSET, 0x00),
            (BAG_COUNT, 1),
            (BAG_ITEMS_START, 0x14), // POTION
            (BAG_ITEMS_START + 1, 2),
            (BATTLE_TYPE, 0),
            (JOY_IGNORE, 0),
        ]);

        let state = read_game_state(&mut mem);

        assert_eq!(state.map_name, "PEWTER_CITY");
        assert_eq!(state.player_x, 10);
        assert_eq!(state.player_y, 5);
        assert!(!state.in_battle);
        assert_eq!(state.badge_count, 1);
        assert_eq!(state.badges, vec!["BOULDER"]);
        assert_eq!(state.money, 3456);
        assert_eq!(state.party.len(), 1);
        assert_eq!(state.party[0].species, "CHARMANDER");
        assert_eq!(state.party[0].level, 14);
        assert_eq!(state.bag.len(), 1);
        assert_eq!(state.bag[0].name, "POTION");
        assert!(matches!(state.special_ui, SpecialUiState::None));
    }
}
