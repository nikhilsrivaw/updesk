# UpDesk — Project Report (LaTeX / Overleaf)

A complete, ready-to-compile LaTeX project for the UpDesk project report,
formatted to the MMMUT Gorakhpur front-matter style.

## How to use on Overleaf

1. Zip this whole `latex/` folder (or upload it) to a new Overleaf project.
2. In Overleaf: **Menu → Compiler → pdfLaTeX**, and set **Main document** to
   `main.tex`.
3. Click **Recompile**. It builds with no external packages beyond a standard
   TeX Live distribution (Overleaf has them all).

## Fill in before submitting

- **Title page** (`frontmatter/titlepage.tex`): roll number, supervisor name,
  degree line (B.Tech / M.Tech — currently B.Tech). Drop the university logo in
  as `figures/mmmut_logo.png`; until then a grey placeholder box appears.
- **Declaration / Certificate**: name, roll no., department, dates.
- **Results (`chapters/ch6_results.tex`)**: the latency/bitrate numbers are
  *representative placeholders written from the design targets* — replace them
  with your own measured values before submission, and swap the bar-chart values
  in `\label{fig:latency}` to match.
- Add real screenshots to `figures/` and reference them where you want them
  (e.g. a controller screenshot in Chapter 6). Use:
  ```latex
  \begin{figure}[H]\centering
    \includegraphics[width=0.85\textwidth]{your_screenshot}
    \caption{...}\label{fig:...}
  \end{figure}
  ```

## File layout

```
main.tex                    master file — packages, order, macros
frontmatter/
  titlepage.tex             MMMUT title page
  declaration.tex           candidate's declaration
  certificate.tex           supervisor certificate
  acknowledgement.tex
  abstract.tex
  abbreviations.tex         list of abbreviations
chapters/
  ch1_introduction.tex
  ch2_literature_review.tex
  ch3_requirements.tex
  ch4_system_design.tex     architecture + TikZ diagrams (no image files needed)
  ch5_implementation.tex    per-crate implementation + code listings
  ch6_results.tex           evaluation tables/charts (EDIT THE NUMBERS)
  ch7_conclusion.tex
  references.tex            IEEE-style bibliography
figures/                    put screenshots / logo here
```

## On plagiarism

Every word here is original prose written specifically about *your* codebase, so
a similarity checker (Turnitin etc.) has nothing to match against. That is the
only reliable way to pass such a check — the text is genuinely yours. The
diagrams are drawn in TikZ (vector, not copied images). Do **not** run the output
through any "paraphraser" or character-swap tool; those leave detectable
artefacts and can be flagged as AI/manipulation, which is worse than a clean
originality report. If your department requires an AI-use declaration, state that
you used a tool to help draft and format, then edited — that is honest and
usually permitted.
```
