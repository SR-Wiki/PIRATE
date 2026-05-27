# PIRATE

PIRATE is an ImageJ/Fiji plugin for quantitative fluorescence intensity standardization in biological imaging. It provides a graphical interface for Max, Min-Max, Percentile, and PIRATE normalization workflows, with support for single images and image stacks.

> This repository is the renamed and updated open-source release of the earlier APN plugin project.

## Preview

### GUI

![PIRATE GUI](examples/GUI.png)

### Example Output

| Raw image | PIRATE output |
| --- | --- |
| ![Raw image](examples/RAW.png) | ![PIRATE output](examples/APN.png) |

## Features

- ImageJ/Fiji plugin with a Swing-based graphical interface.
- PIRATE adaptive patch-based normalization.
- Baseline Max, Min-Max, and Percentile normalization modes.
- Histogram visualization for single-image runs.
- Stack processing using the model estimated from the first slice.
- Bundled PIRATE logo asset for the plugin interface.

## Installation

### Option 1: Use the bundled JAR

1. Copy `pirate_1.0.0.jar` into the `plugins/` folder of Fiji or ImageJ.
2. Restart Fiji/ImageJ.
3. Open an image and run `Plugins > PIRATE > PIRATE`.

### Option 2: Download a release JAR

1. Download `pirate_1.0.0.jar` from the GitHub Releases page.
2. Copy `pirate_1.0.0.jar` into the `plugins/` folder of Fiji or ImageJ.
3. Restart Fiji/ImageJ.
4. Open an image and run `Plugins > PIRATE > PIRATE`.

### Option 3: Build from source

Requirements:

- Java 8 or later
- Maven 3.x
- Fiji or ImageJ

Build:

```bash
mvn clean package
```

The plugin JAR will be created under:

```text
target/pirate_1.0.0.jar
```

Copy this JAR into the Fiji/ImageJ `plugins/` folder, then restart Fiji/ImageJ.

## Usage

1. Open an 8-bit, 16-bit, or 32-bit grayscale image or stack in Fiji/ImageJ.
2. Run `Plugins > PIRATE > PIRATE`.
3. Select a normalization mode:
   - `PIRATE`: adaptive patch-based transform filter.
   - `Percentile normalization`: user-defined lower and upper percentiles.
   - `Min-Max normalization`: occupied intensity range.
   - `Max normalization`: scale by maximum occupied intensity.
4. Choose whether to show the histogram.
5. Click `START PROCESS`.

For stacks, PIRATE estimates normalization parameters from the first slice and applies the resulting model to every slice.

## Repository Layout

```text
.
|-- pirate_1.0.0.jar
|-- pom.xml
|-- src/main/java/org/srwiki/pirate/PIRATE_Normalization.java
|-- src/main/resources/plugins.config
|-- src/main/resources/pirate_logo_3x4_transparent.png
|-- src/main/resources/1.png
`-- examples/
```

## Citation

The associated paper is not yet published. If you use PIRATE before a paper or preprint is available, please cite this GitHub repository. A formal paper citation or preprint link will be added after release.

## License

This project is released under the Open Data Commons Open Database License v1.0 (`ODbL-1.0`). See `LICENSE`.

## Previous Project

The earlier project was hosted at:

```text
https://github.com/SR-Wiki/APN
```

Use this repository for the renamed and updated PIRATE release:

```text
https://github.com/SR-Wiki/PIRATE
```

