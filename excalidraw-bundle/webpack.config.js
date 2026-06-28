const fs = require("fs");
const path = require("path");
const HtmlWebpackPlugin = require("html-webpack-plugin");
const MiniCssExtractPlugin = require("mini-css-extract-plugin");

const outputPath = path.resolve(
  __dirname,
  "../src/main/resources/webview"
);

// Font loading workaround.
//
// The webview is served from the `excalidraw://` custom scheme, whose document
// origin is opaque ("null"). That makes every URL-based font load (fetch /
// FontFace(url) / CSS @font-face url()) *cross-origin*, which Chromium blocks
// before the request even reaches our scheme handler — so Excalidraw's canvas
// fonts (Excalifont, Nunito, Comic Shanns, …) never load and text falls back.
//
// Instead of URLs, we embed the woff2 bytes as base64 and (in index.html) patch
// the FontFace constructor to build faces from an ArrayBuffer — no network, no
// CORS, no origin involved. This plugin emits webview/fonts-embed.js:
//   window.__EXCALIDRAW_FONT_BYTES__ = { "fonts/<Family>/<file>.woff2": "<b64>", … }
// keyed by the path that appears in Excalidraw's font URLs.
//
// The package ships ~13 MB of fonts, but 12 MB of that is Xiaolai (CJK). We
// embed every family EXCEPT Xiaolai (~0.5 MB raw) — enough for the default
// Excalifont and all Latin text. Add "Xiaolai" back here for CJK canvas text.
const FONTS_SRC = path.resolve(
  __dirname,
  "node_modules/@excalidraw/excalidraw/dist/prod/fonts"
);
const EXCLUDED_FONT_DIRS = ["Xiaolai"];

class EmbedExcalidrawFontsPlugin {
  apply(compiler) {
    // afterEmit runs after webpack's output.clean has wiped the dir.
    compiler.hooks.afterEmit.tapPromise("EmbedExcalidrawFonts", async () => {
      const map = {};
      const walk = (dir) => {
        for (const name of fs.readdirSync(dir)) {
          const full = path.join(dir, name);
          if (EXCLUDED_FONT_DIRS.includes(name)) continue;
          const st = fs.statSync(full);
          if (st.isDirectory()) {
            walk(full);
          } else if (name.endsWith(".woff2")) {
            const rel = "fonts/" + path.relative(FONTS_SRC, full).split(path.sep).join("/");
            map[rel] = fs.readFileSync(full).toString("base64");
          }
        }
      };
      walk(FONTS_SRC);
      const out = "window.__EXCALIDRAW_FONT_BYTES__ = " + JSON.stringify(map) + ";\n";
      fs.writeFileSync(path.join(outputPath, "fonts-embed.js"), out);
    });
  }
}

module.exports = {
  entry: "./src/index.jsx",
  output: {
    path: outputPath,
    filename: "bundle.js",
    clean: true,
  },
  resolve: {
    extensions: [".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs"],
    fallback: {
      path: false,
      fs: false,
    },
  },
  module: {
    rules: [
      // Allow ESM modules in node_modules to omit file extensions (roughjs compat).
      {
        test: /\.m?js$/,
        resolve: {
          fullySpecified: false,
        },
      },
      {
        test: /\.(js|jsx|ts|tsx)$/,
        exclude: /node_modules/,
        use: {
          loader: "babel-loader",
          options: {
            presets: [
              ["@babel/preset-env", { targets: "defaults" }],
              ["@babel/preset-react", { runtime: "automatic" }],
              "@babel/preset-typescript",
            ],
          },
        },
      },
      {
        test: /\.css$/,
        use: [MiniCssExtractPlugin.loader, "css-loader"],
      },
      {
        test: /\.(png|svg|jpg|jpeg|gif|woff|woff2|eot|ttf|otf)$/i,
        type: "asset/resource",
      },
    ],
  },
  plugins: [
    new HtmlWebpackPlugin({
      template: "./src/index.html",
      filename: "index.html",
    }),
    new MiniCssExtractPlugin({
      filename: "bundle.css",
    }),
    new EmbedExcalidrawFontsPlugin(),
  ],
  performance: {
    hints: false,
  },
};
