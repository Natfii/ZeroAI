// Copyright (c) 2026 @Natfii. All rights reserved.

//! Boytacean emulator wrapper for the ClawBoy subsystem.
//!
//! Provides a safe, ergonomic interface over the Boytacean Game Boy
//! emulator crate. Handles ROM loading, frame advancement, memory
//! reads, input injection, save states, and screenshot capture.

use boytacean::gb::{GameBoy, GameBoyMode};
use boytacean::pad::PadKey;
use boytacean::ppu::{DISPLAY_HEIGHT, DISPLAY_WIDTH};
use boytacean::state::StateManager;
use sha1::{Digest, Sha1};

use super::memory_map::EXPECTED_SHA1;
use super::types::{GbButton, RomVerification};

/// Game Boy screen width in pixels.
#[allow(dead_code, clippy::cast_possible_truncation)]
const GB_WIDTH: u32 = DISPLAY_WIDTH as u32;

/// Game Boy screen height in pixels.
#[allow(dead_code, clippy::cast_possible_truncation)]
const GB_HEIGHT: u32 = DISPLAY_HEIGHT as u32;

/// Wrapper around the Boytacean Game Boy emulator.
///
/// Provides a safe, ergonomic interface for ROM loading, frame
/// advancement, memory reads, input injection, and state persistence.
pub struct Emulator {
    /// The underlying Boytacean emulator instance.
    gb: GameBoy,
}

impl Emulator {
    /// Creates a new emulator instance and loads the given ROM.
    ///
    /// Initializes a DMG-mode (original Game Boy) emulator, loads the
    /// ROM data with optional battery save (SRAM), and skips the boot
    /// ROM animation to go straight to the game.
    ///
    /// # Errors
    ///
    /// Returns an error string if the ROM data is invalid or the
    /// emulator fails to initialize.
    pub fn new(rom_data: &[u8], battery_save: Option<&[u8]>) -> Result<Self, String> {
        let mut gb = GameBoy::new(Some(GameBoyMode::Dmg));
        gb.load_rom(rom_data, battery_save)
            .map_err(|e| format!("failed to load ROM: {e}"))?;
        // Use boot=true to run the built-in DmgBootix boot ROM.
        // boot=false skips the boot animation but leaves SVBK/WRAM
        // bank registers uninitialised, causing a panic when Pokemon
        // Red writes to 0xFF70 (boytacean doesn't guard DMG mode).
        gb.load(true)
            .map_err(|e| format!("failed to initialize emulator: {e}"))?;
        Ok(Self { gb })
    }

    /// Advances the emulator by one full frame (~70224 CPU cycles).
    ///
    /// Returns the number of CPU cycles executed during the frame.
    pub fn tick_frame(&mut self) -> u32 {
        self.gb.next_frame()
    }

    /// Reads a single byte from the emulator's memory bus.
    ///
    /// This accesses the full 16-bit address space including ROM, RAM,
    /// VRAM, I/O registers, and HRAM.
    #[allow(dead_code)]
    pub fn read_memory(&mut self, addr: u16) -> u8 {
        self.gb.read_memory(addr)
    }

    /// Returns the current frame buffer in RGB565 format.
    ///
    /// The returned buffer is 160x144 pixels at 2 bytes per pixel
    /// (46,080 bytes total). This is the most compact format suitable
    /// for streaming to WebSocket viewers.
    pub fn frame_buffer_rgb565(&mut self) -> Vec<u8> {
        self.gb.frame_buffer_rgb565().to_vec()
    }

    /// Captures a PNG screenshot of the current frame.
    ///
    /// Reads the RGB frame buffer and encodes it as a 160x144 RGB8
    /// PNG image. Suitable for saving screenshots or sending to LLM
    /// vision APIs.
    ///
    /// # Errors
    ///
    /// Returns an error string if PNG encoding fails.
    #[allow(dead_code)]
    pub fn capture_screenshot_png(&mut self) -> Result<Vec<u8>, String> {
        let rgb_data = self.gb.frame_buffer_eager();
        let mut png_bytes: Vec<u8> = Vec::new();

        {
            let mut encoder = png::Encoder::new(&mut png_bytes, GB_WIDTH, GB_HEIGHT);
            encoder.set_color(png::ColorType::Rgb);
            encoder.set_depth(png::BitDepth::Eight);

            let mut writer = encoder
                .write_header()
                .map_err(|e| format!("PNG header error: {e}"))?;
            writer
                .write_image_data(&rgb_data)
                .map_err(|e| format!("PNG write error: {e}"))?;
        }

        Ok(png_bytes)
    }

    /// Presses a Game Boy button (the button stays held until released).
    #[allow(dead_code)]
    pub fn key_press(&mut self, button: GbButton) {
        self.gb.key_press(to_pad_key(button));
    }

    /// Releases a Game Boy button.
    #[allow(dead_code)]
    pub fn key_lift(&mut self, button: GbButton) {
        self.gb.key_lift(to_pad_key(button));
    }

    /// Releases all eight Game Boy buttons.
    ///
    /// Useful for resetting input state between agent decisions to
    /// prevent buttons from being held unintentionally.
    #[allow(dead_code)]
    pub fn release_all_keys(&mut self) {
        for button in ALL_BUTTONS {
            self.gb.key_lift(to_pad_key(button));
        }
    }

    /// Serializes the full emulator state to a byte buffer.
    ///
    /// Uses Boytacean's BOSC (compressed) save state format. The
    /// returned data can be loaded later with [`load_state`](Self::load_state)
    /// to restore the exact emulator state.
    ///
    /// # Errors
    ///
    /// Returns an error string if state serialization fails.
    pub fn save_state(&mut self) -> Result<Vec<u8>, String> {
        StateManager::save(&mut self.gb, None, None).map_err(|e| format!("save state failed: {e}"))
    }

    /// Restores a previously saved emulator state.
    ///
    /// Accepts data produced by [`save_state`](Self::save_state) and
    /// restores the emulator to that exact point in time.
    ///
    /// # Errors
    ///
    /// Returns an error string if the state data is invalid or
    /// corrupted.
    pub fn load_state(&mut self, data: &[u8]) -> Result<(), String> {
        StateManager::load(data, &mut self.gb, None, None)
            .map_err(|e| format!("load state failed: {e}"))
    }

    /// Returns the cartridge's battery-backed SRAM data.
    ///
    /// This is the persistent save data that survives power-off in
    /// real hardware. Used to persist in-game saves across app
    /// restarts.
    pub fn battery_save(&mut self) -> Vec<u8> {
        self.gb.ram_data_eager()
    }

    /// Loads battery-backed SRAM data into the cartridge.
    ///
    /// Restores in-game save data from a previous session. This
    /// does not affect the emulator state (registers, VRAM, etc.),
    /// only the cartridge's battery-backed RAM.
    #[allow(dead_code)]
    pub fn load_battery_save(&mut self, data: &[u8]) {
        self.gb.set_ram_data(data.to_vec());
    }

    /// Verifies a ROM file against the expected Pokemon Red SHA-1 hash.
    ///
    /// Computes the SHA-1 digest of the provided ROM data and compares
    /// it to the known-good No-Intro verified dump hash. Returns a
    /// [`RomVerification`] with the result and computed hash.
    pub fn verify_rom(data: &[u8]) -> RomVerification {
        let mut hasher = Sha1::new();
        hasher.update(data);
        let digest = hasher.finalize();
        let sha1_hex = format!("{digest:X}");
        let valid = sha1_hex == EXPECTED_SHA1;
        RomVerification {
            valid,
            sha1: sha1_hex,
        }
    }
}

/// All eight Game Boy buttons for iteration.
#[allow(dead_code)]
const ALL_BUTTONS: [GbButton; 8] = [
    GbButton::A,
    GbButton::B,
    GbButton::Start,
    GbButton::Select,
    GbButton::Up,
    GbButton::Down,
    GbButton::Left,
    GbButton::Right,
];

/// Converts a [`GbButton`] to the Boytacean [`PadKey`] equivalent.
#[allow(dead_code)]
fn to_pad_key(button: GbButton) -> PadKey {
    match button {
        GbButton::A => PadKey::A,
        GbButton::B => PadKey::B,
        GbButton::Start => PadKey::Start,
        GbButton::Select => PadKey::Select,
        GbButton::Up => PadKey::Up,
        GbButton::Down => PadKey::Down,
        GbButton::Left => PadKey::Left,
        GbButton::Right => PadKey::Right,
    }
}
