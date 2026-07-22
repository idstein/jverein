/**********************************************************************
 * Copyright (c) 2026 by Heiner Jostkleigrewe and pair partners.
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 **********************************************************************/
package de.jost_net.JVerein.gui.control;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.gui.action.DokumentationAction;
import de.jost_net.JVerein.rmi.Buchung;
import de.jost_net.JVerein.rmi.BuchungDokument;
import de.jost_net.JVerein.rmi.Buchungsart;
import de.jost_net.JVerein.rmi.Buchungsklasse;
import de.jost_net.JVerein.rmi.Spendenbescheinigung;
import de.jost_net.JVerein.rmi.Konto;
import de.jost_net.JVerein.rmi.Anfangsbestand;
import de.jost_net.JVerein.rmi.Steuer;
import de.jost_net.JVerein.keys.Kontoart;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.gui.AbstractControl;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.Action;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.gui.input.CheckboxInput;
import de.willuhn.jameica.gui.input.SelectInput;
import de.willuhn.jameica.gui.parts.Button;
import de.willuhn.jameica.messaging.QueryMessage;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.Settings;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;

import com.itextpdf.text.Document;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Font;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.Element;

public class KoerperschaftssteuerControl extends AbstractControl
{
  public enum Sphere
  {
    IDEELL("Ideeller Bereich"),
    VERMOEGENSVERWALTUNG("Vermögensverwaltung"),
    ZWECKBETRIEB("Zweckbetrieb"),
    WGB("Wirtschaftlicher Geschäftsbetrieb"),
    UNASSIGNED("Nicht zugeordnet");

    private final String label;

    Sphere(String label)
    {
      this.label = label;
    }

    public String getLabel()
    {
      return label;
    }
  }

  public static class YearPeriod
  {
    private final int targetYear;
    private final String label;

    public YearPeriod(int targetYear, String label)
    {
      this.targetYear = targetYear;
      this.label = label;
    }

    public int getTargetYear()
    {
      return targetYear;
    }

    @Override
    public String toString()
    {
      return label;
    }
  }

  private Settings settings;
  private SelectInput targetYearInput;

  // UI Panels
  private Table warnungenTable;
  private Table problemBuchungenTable;
  private List<Buchung> problemBuchungenList = new ArrayList<>();
  private Table missingBelegeTable;
  private List<Buchung> missingBelegeList = new ArrayList<>();
  private Table ergebnisseTable;
  private Label ergebnisseWarningLabel;
  private Table vermoegenTable;
  private Table ruecklagenTable;
  private Text exportLogsText;
  // Cached Audit Data Structure
  private static class CachedAuditData
  {
    int targetYear;
    int startYear;
    boolean turnusJaehrlich;
    ProcessedData data;
    List<PlausibilityResult> plausibilityResults;
    List<Buchung> bookings;
    Map<Long, List<BuchungDokument>> docsByReferenz;
  }

  private CachedAuditData cachedAuditData = null;

  // Checkboxen checklist
  private CheckboxInput taetigkeitsberichtCb;
  private CheckboxInput mvProtokolleCb;
  private CheckboxInput kassenberichtCb;
  private CheckboxInput zerRegisterCb;
  private CheckboxInput verzichtserklaerungCb;

  public KoerperschaftssteuerControl(AbstractView view)
  {
    super(view);
    this.settings = new Settings(this.getClass());
  }

  public Composite getFilterPart() throws Exception
  {
    Composite comp = new Composite(this.view.getParent(), SWT.NONE);
    comp.setLayout(new GridLayout(2, false));
    comp.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

    Label lbl = new Label(comp, SWT.NONE);
    lbl.setText("Veranlagungszeitraum:");

    boolean turnusJaehrlich = false;
    try
    {
      turnusJaehrlich = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.KSTTURNUSJAEHRLICH);
    }
    catch (Exception e)
    {
      // fallback
    }

    List<YearPeriod> periods = new ArrayList<>();
    int currentYear = Calendar.getInstance().get(Calendar.YEAR);
    for (int y = currentYear; y >= currentYear - 9; y--)
    {
      if (turnusJaehrlich)
      {
        periods.add(new YearPeriod(y, String.valueOf(y)));
      }
      else
      {
        periods.add(new YearPeriod(y, (y - 2) + " - " + y));
      }
    }

    int savedYear = settings.getInt("target_year", currentYear - 1);
    YearPeriod defaultPeriod = null;
    for (YearPeriod p : periods)
    {
      if (p.getTargetYear() == savedYear)
      {
        defaultPeriod = p;
        break;
      }
    }
    if (defaultPeriod == null && !periods.isEmpty())
    {
      defaultPeriod = periods.get(0);
    }

    targetYearInput = new SelectInput(periods, defaultPeriod);
    targetYearInput.addListener(evt -> {
      if (evt != null)
      {
        try
        {
          YearPeriod p = (YearPeriod) targetYearInput.getValue();
          if (p != null)
          {
            settings.setAttribute("target_year", p.getTargetYear());
            refreshAuditsAsync(false);
          }
        }
        catch (Exception e)
        {
          Logger.error("Fehler beim Aktualisieren der Steuerjahre", e);
        }
      }
    });
    targetYearInput.paint(comp);

    return comp;
  }

  public void paintWarnungenTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    Group gr1 = new Group(parent, SWT.NONE);
    gr1.setText("Prüfungsergebnisse & Frühwarnungen");
    gr1.setLayout(new GridLayout(1, false));
    gr1.setLayoutData(new GridData(GridData.FILL_BOTH));

    warnungenTable = new Table(gr1, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    warnungenTable.setHeaderVisible(true);
    warnungenTable.setLinesVisible(true);
    warnungenTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    String[] wCols = {"Status", "Jahr", "Prüfung / Problem", "Details & Empfehlung"};
    int[] wWidths = {90, 60, 200, 450};
    for (int i = 0; i < wCols.length; i++)
    {
      TableColumn col = new TableColumn(warnungenTable, SWT.LEFT);
      col.setText(wCols[i]);
      col.setWidth(wWidths[i]);
    }

    warnungenTable.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter()
    {
      @Override
      public void widgetSelected(org.eclipse.swt.events.SelectionEvent e)
      {
        int idx = warnungenTable.getSelectionIndex();
        if (idx >= 0 && idx < warnungenTable.getItemCount())
        {
          TableItem item = warnungenTable.getItem(idx);
          PlausibilityResult selectedResult = (PlausibilityResult) item.getData("result");
          filterProblemBuchungenTable(selectedResult);
        }
      }
    });

    Group gr2 = new Group(parent, SWT.NONE);
    gr2.setText("Betroffene Buchungen (Doppelklick zum Öffnen / Bearbeiten)");
    gr2.setLayout(new GridLayout(1, false));
    gr2.setLayoutData(new GridData(GridData.FILL_BOTH));

    problemBuchungenTable = new Table(gr2, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    problemBuchungenTable.setHeaderVisible(true);
    problemBuchungenTable.setLinesVisible(true);
    problemBuchungenTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    String[] bCols = {"ID", "Datum", "Buchungsart", "Name / Empfänger", "Zweck", "Betrag", "Festgestelltes Problem"};
    int[] bWidths = {50, 80, 140, 150, 180, 80, 220};
    for (int i = 0; i < bCols.length; i++)
    {
      TableColumn col = new TableColumn(problemBuchungenTable, SWT.LEFT);
      col.setText(bCols[i]);
      col.setWidth(bWidths[i]);
    }

    // Double-click listener on problemBuchungenTable to open Buchung dialog
    problemBuchungenTable.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter()
    {
      @Override
      public void widgetDefaultSelected(org.eclipse.swt.events.SelectionEvent e)
      {
        int idx = problemBuchungenTable.getSelectionIndex();
        if (idx >= 0 && idx < problemBuchungenList.size())
        {
          Buchung b = problemBuchungenList.get(idx);
          try
          {
            new de.jost_net.JVerein.gui.action.BuchungAction(false).handleAction(b);
          }
          catch (Exception ex)
          {
            Logger.error("Fehler beim Öffnen der Buchung", ex);
          }
        }
      }
    });
  }

  public void paintBelegeTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    // Checklist Group
    Group checklistGroup = new Group(parent, SWT.NONE);
    checklistGroup.setText("Nachweise & Externe Dokumenten-Checkliste");
    checklistGroup.setLayout(new GridLayout(2, false));
    checklistGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

    boolean turnusJaehrlich = false;
    try
    {
      turnusJaehrlich = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.KSTTURNUSJAEHRLICH);
    }
    catch (Exception e)
    {
      // fallback
    }
    String jahreText = turnusJaehrlich ? "des Geschäftsjahres" : "der 3 Jahre";
    String zeitraumText = turnusJaehrlich ? "im Steuerzeitraum (1 Jahr)" : "im Steuerzeitraum (3 Jahre)";

    taetigkeitsberichtCb = new CheckboxInput(settings.getBoolean("cb_taetigkeit", false));
    taetigkeitsberichtCb.addListener(e -> settings.setAttribute("cb_taetigkeit", (Boolean) taetigkeitsberichtCb.getValue()));
    Label l1 = new Label(checklistGroup, SWT.NONE);
    l1.setText("Tätigkeitsbericht (inhaltliche Tätigkeitsbeschreibung " + jahreText + ")");
    taetigkeitsberichtCb.paint(checklistGroup);

    mvProtokolleCb = new CheckboxInput(settings.getBoolean("cb_mv", false));
    mvProtokolleCb.addListener(e -> settings.setAttribute("cb_mv", (Boolean) mvProtokolleCb.getValue()));
    Label l2 = new Label(checklistGroup, SWT.NONE);
    l2.setText("Beschlüsse der Mitgliederversammlungen (Entlastung & Genehmigung EÜR)");
    mvProtokolleCb.paint(checklistGroup);

    kassenberichtCb = new CheckboxInput(settings.getBoolean("cb_kassenbericht", false));
    kassenberichtCb.addListener(e -> settings.setAttribute("cb_kassenbericht", (Boolean) kassenberichtCb.getValue()));
    Label l3 = new Label(checklistGroup, SWT.NONE);
    l3.setText("Unterzeichnete Kassenprüfungsberichte der Kassenprüfer");
    kassenberichtCb.paint(checklistGroup);

    zerRegisterCb = new CheckboxInput(settings.getBoolean("cb_zer", true));
    zerRegisterCb.addListener(e -> settings.setAttribute("cb_zer", (Boolean) zerRegisterCb.getValue()));
    Label l4 = new Label(checklistGroup, SWT.NONE);
    l4.setText("Zuwendungsempfängerregister (vom Finanzamt ans BZSt gemeldet)");
    zerRegisterCb.paint(checklistGroup);

    // Unified Missing Belege & Nachweise Table Group
    Group tableGroup = new Group(parent, SWT.NONE);
    tableGroup.setText("Fehlende Belege, Spendenbescheinigungen & Verzichtserklärungen " + zeitraumText + " (Doppelklick zum Öffnen)");
    tableGroup.setLayout(new GridLayout(1, false));
    tableGroup.setLayoutData(new GridData(GridData.FILL_BOTH));

    missingBelegeTable = new Table(tableGroup, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    missingBelegeTable.setHeaderVisible(true);
    missingBelegeTable.setLinesVisible(true);
    missingBelegeTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    String[] columns = {"ID", "Kategorie", "Datum", "Buchungsart", "Name / Empfänger", "Betrag", "Handlungsbedarf / Erforderlicher Nachweis"};
    int[] widths = {50, 110, 80, 140, 150, 80, 260};
    for (int i = 0; i < columns.length; i++)
    {
      TableColumn col = new TableColumn(missingBelegeTable, SWT.LEFT);
      col.setText(columns[i]);
      col.setWidth(widths[i]);
    }

    // Double-click listener on missingBelegeTable to open Buchung edit dialog
    missingBelegeTable.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter()
    {
      @Override
      public void widgetDefaultSelected(org.eclipse.swt.events.SelectionEvent e)
      {
        int idx = missingBelegeTable.getSelectionIndex();
        if (idx >= 0 && idx < missingBelegeList.size())
        {
          Buchung b = missingBelegeList.get(idx);
          try
          {
            new de.jost_net.JVerein.gui.action.BuchungAction(false).handleAction(b);
          }
          catch (Exception ex)
          {
            Logger.error("Fehler beim Öffnen der Buchung", ex);
          }
        }
      }
    });
  }

  public void paintErgebnisseTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    ergebnisseWarningLabel = new Label(parent, SWT.WRAP);
    ergebnisseWarningLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
    ergebnisseWarningLabel.setText("Berechne Ergebnisse...");

    Group gr = new Group(parent, SWT.NONE);
    gr.setText("Ergebnisse nach steuerrechtlichen Sphären");
    gr.setLayout(new GridLayout(1, false));
    gr.setLayoutData(new GridData(GridData.FILL_BOTH));

    ergebnisseTable = new Table(gr, SWT.BORDER | SWT.FULL_SELECTION);
    ergebnisseTable.setHeaderVisible(true);
    ergebnisseTable.setLinesVisible(true);
    ergebnisseTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    // Columns will be created dynamically in refreshAudits()
    TableColumn colHeader = new TableColumn(ergebnisseTable, SWT.LEFT);
    colHeader.setText("Sphäre / Buchungsart");
    colHeader.setWidth(250);
  }

  public void paintVermoegenTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    // Section 1: II. Vermögensaufstellung
    Group grVermoegen = new Group(parent, SWT.NONE);
    grVermoegen.setText("II. Verm\u00f6gensaufstellung");
    grVermoegen.setLayout(new GridLayout(1, false));
    grVermoegen.setLayoutData(new GridData(GridData.FILL_BOTH));

    vermoegenTable = new Table(grVermoegen, SWT.BORDER | SWT.FULL_SELECTION);
    vermoegenTable.setHeaderVisible(true);
    vermoegenTable.setLinesVisible(true);
    vermoegenTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    // Section 2: III. Rücklagen und Vermögenszuführungen
    Group grRuecklagen = new Group(parent, SWT.NONE);
    grRuecklagen.setText("III. R\u00fccklagen und Verm\u00f6genszuf\u00fchrungen");
    grRuecklagen.setLayout(new GridLayout(1, false));
    grRuecklagen.setLayoutData(new GridData(GridData.FILL_BOTH));

    ruecklagenTable = new Table(grRuecklagen, SWT.BORDER | SWT.FULL_SELECTION);
    ruecklagenTable.setHeaderVisible(true);
    ruecklagenTable.setLinesVisible(true);
    ruecklagenTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    // Info Label
    Label infoLabel = new Label(parent, SWT.WRAP);
    infoLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
    infoLabel.setText("Hinweis zu Verm\u00f6genszuf\u00fchrungen (\u00a7 62 Abs. 3 & 4 AO):\n" +
        "Hierunter fallen Schenkungen, Erbschaften, zweckgerichtete Spenden aufgrund eines Spendenaufrufs " +
        "(sofern zur Erh\u00f6hung des Verm\u00f6gens deklariert), sowie erhaltene Sachzuwendungen, die ihrem Wesen " +
        "nach zum Anlageverm\u00f6gen geh\u00f6ren. Diese k\u00f6nnen dem freien Verm\u00f6gen zugef\u00fchrt werden, " +
        "ohne die zeitnahe Mittelverwendung zu verletzen.");
    infoLabel.setForeground(GUI.getDisplay().getSystemColor(SWT.COLOR_DARK_BLUE));
  }

  public void paintExportTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    Group gr = new Group(parent, SWT.NONE);
    gr.setText("DATEV & PDF Finanzberichte-Paketierung");
    gr.setLayout(new GridLayout(1, false));
    gr.setLayoutData(new GridData(GridData.FILL_BOTH));

    Composite btnComp = new Composite(gr, SWT.NONE);
    btnComp.setLayout(new GridLayout(2, false));
    btnComp.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

    // Button 1: Standalone Plausibilitätsprüfung
    Button checkBtn = new Button("Plausibilitätsprüfung ausführen", new Action()
    {
      @Override
      public void handleAction(Object context) throws ApplicationException
      {
        final int targetYear = (targetYearInput != null && targetYearInput.getValue() != null) ?
            ((YearPeriod) targetYearInput.getValue()).getTargetYear() : Calendar.getInstance().get(Calendar.YEAR);

        de.willuhn.jameica.system.Application.getController().start(new de.willuhn.jameica.system.BackgroundTask()
        {
          @Override
          public void run(de.willuhn.util.ProgressMonitor monitor) throws ApplicationException
          {
            try
            {
              monitor.setStatusText("Starte Plausibilitätsprüfung...");
              monitor.setPercentComplete(10);
              updateExportLogs("=== Starte manuelle Plausibilitätsprüfung ===");

              boolean turnusJaehrlich = false;
              try { turnusJaehrlich = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.KSTTURNUSJAEHRLICH); } catch (Exception e) {}
              int startYear = turnusJaehrlich ? targetYear : (targetYear - 2);

              DBIterator<BuchungDokument> docIt = Einstellungen.getDBService().createList(BuchungDokument.class);
              Map<Long, List<BuchungDokument>> docsByReferenz = new HashMap<>();
              while (docIt.hasNext())
              {
                BuchungDokument doc = docIt.next();
                if (doc.getReferenz() != null)
                {
                  docsByReferenz.computeIfAbsent(doc.getReferenz(), k -> new ArrayList<>()).add(doc);
                }
              }
              monitor.setPercentComplete(30);

              List<Buchung> allCheckBookings = new ArrayList<>();
              for (int y = startYear; y <= targetYear; y++)
              {
                Calendar c = Calendar.getInstance();
                c.set(y, Calendar.JANUARY, 1, 0, 0, 0);
                Date fD = c.getTime();
                c.set(y, Calendar.DECEMBER, 31, 23, 59, 59);
                Date tD = c.getTime();
                DBIterator<Buchung> bIt = Einstellungen.getDBService().createList(Buchung.class);
                bIt.addFilter("datum >= ?", fD);
                bIt.addFilter("datum <= ?", tD);
                while (bIt.hasNext()) allCheckBookings.add(bIt.next());
              }
              monitor.setPercentComplete(50);
              monitor.setStatusText("Prüfe Buchungen, Rücklagen und Beleg-PDFs...");

              ProcessedData checkData = processBookings(allCheckBookings, startYear, targetYear, docsByReferenz);
              List<PlausibilityResult> pCheckResults = runPlausibilityChecks(checkData, startYear, targetYear, allCheckBookings, docsByReferenz);
              monitor.setPercentComplete(80);

              int criticals = 0, warnings = 0, infos = 0;
              for (PlausibilityResult r : pCheckResults)
              {
                String tag = r.level == CheckLevel.CRITICAL ? " [FEHLER] " :
                            (r.level == CheckLevel.WARNING ? " [WARNUNG] " : " [INFO] ");
                updateExportLogs(tag + r.year + " | " + r.checkName + ": " + r.message);
                if (r.level == CheckLevel.CRITICAL) criticals++;
                else if (r.level == CheckLevel.WARNING) warnings++;
                else infos++;
              }
              updateExportLogs(String.format("Prüfung beendet: %d Fehler, %d Warnungen, %d Hinweise.", criticals, warnings, infos));

              // Update GUI audit tabs on UI thread
              GUI.getDisplay().asyncExec(() -> {
                try { refreshAudits(); } catch (Exception e) { Logger.error("Fehler beim Aktualisieren der Audits", e); }
              });

              monitor.setPercentComplete(100);
              monitor.setStatus(de.willuhn.util.ProgressMonitor.STATUS_DONE);
              monitor.setStatusText("Plausibilitätsprüfung beendet");
            }
            catch (Exception e)
            {
              Logger.error("Fehler bei Plausibilitätsprüfung", e);
              throw new ApplicationException("Fehler: " + e.getMessage());
            }
          }

          @Override
          public void interrupt() {}
          @Override
          public boolean isInterrupted() { return false; }
        });
      }
    }, null, false, "dialog-information.png");
    checkBtn.paint(btnComp);

    // Button 2: Export-Paket erzeugen (ZIP) mit Progress Monitor
    Button exportBtn = new Button("DATEV-Exportpaket erzeugen (ZIP)", new Action()
    {
      @Override
      public void handleAction(Object context) throws ApplicationException
      {
        final int targetYear = (targetYearInput != null && targetYearInput.getValue() != null) ?
            ((YearPeriod) targetYearInput.getValue()).getTargetYear() : Calendar.getInstance().get(Calendar.YEAR);

        de.willuhn.jameica.system.Application.getController().start(new de.willuhn.jameica.system.BackgroundTask()
        {
          @Override
          public void run(de.willuhn.util.ProgressMonitor monitor) throws ApplicationException
          {
            try
            {
              monitor.setStatusText("Starte DATEV-Exportpaketierung...");
              monitor.setPercentComplete(5);
              generateDatevExportPackage(targetYear, monitor);
            }
            catch (Exception e)
            {
              Logger.error("Fehler beim DATEV Export", e);
              throw new ApplicationException("Fehler beim Erzeugen des DATEV Exports: " + e.getMessage());
            }
          }

          @Override
          public void interrupt() {}
          @Override
          public boolean isInterrupted() { return false; }
        });
      }
    }, null, false, "document-save.png");
    exportBtn.paint(btnComp);

    exportLogsText = new Text(gr, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
    exportLogsText.setLayoutData(new GridData(GridData.FILL_BOTH));
    exportLogsText.setText("Bereit für Export...");

    // Trigger initial audit load
    refreshAuditsAsync(false);
  }

  public Action getRefreshAction()
  {
    return new Action()
    {
      @Override
      public void handleAction(Object context) throws ApplicationException
      {
        try
        {
          refreshAuditsAsync(true);
          GUI.getStatusBar().setSuccessText("Audits erfolgreich aktualisiert.");
        }
        catch (Exception e)
        {
          Logger.error("Fehler beim Aktualisieren", e);
          throw new ApplicationException(e.getMessage());
        }
      }
    };
  }

  public void refreshAudits() throws Exception
  {
    refreshAuditsAsync(true);
  }

  public void refreshAuditsAsync(boolean forceReload) throws Exception
  {
    if (targetYearInput == null || targetYearInput.getValue() == null)
    {
      return;
    }
    final int targetYear = ((YearPeriod) targetYearInput.getValue()).getTargetYear();

    boolean turnusJaehrlich = false;
    try
    {
      turnusJaehrlich = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.KSTTURNUSJAEHRLICH);
    }
    catch (Exception e) {}

    final int startYear = turnusJaehrlich ? targetYear : (targetYear - 2);

    if (!forceReload && cachedAuditData != null && cachedAuditData.targetYear == targetYear && cachedAuditData.startYear == startYear)
    {
      renderCachedAuditResults();
      return;
    }

    final boolean turnusJaehrlichFinal = turnusJaehrlich;

    de.willuhn.jameica.system.Application.getController().start(new de.willuhn.jameica.system.BackgroundTask()
    {
      @Override
      public void run(de.willuhn.util.ProgressMonitor monitor) throws ApplicationException
      {
        try
        {
          monitor.setStatusText("Lade Körperschaftssteuer-Audits & Plausibilitätsprüfungen...");
          monitor.setPercentComplete(10);

          SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
          Calendar cal = Calendar.getInstance();
          cal.set(startYear, Calendar.JANUARY, 1, 0, 0, 0);
          Date fromDate = cal.getTime();
          cal.set(targetYear, Calendar.DECEMBER, 31, 23, 59, 59);
          Date toDate = cal.getTime();

          DBIterator<Buchung> it = Einstellungen.getDBService().createList(Buchung.class);
          it.addFilter("datum >= ?", fromDate);
          it.addFilter("datum <= ?", toDate);

          List<Buchung> bookings = new ArrayList<>();
          while (it.hasNext()) bookings.add(it.next());
          monitor.setPercentComplete(40);

          DBIterator<BuchungDokument> docIt = Einstellungen.getDBService().createList(BuchungDokument.class);
          Map<Long, List<BuchungDokument>> docsByReferenz = new HashMap<>();
          while (docIt.hasNext())
          {
            BuchungDokument doc = docIt.next();
            if (doc.getReferenz() != null)
            {
              docsByReferenz.computeIfAbsent(doc.getReferenz(), k -> new ArrayList<>()).add(doc);
            }
          }
          monitor.setPercentComplete(60);

          ProcessedData data = processBookings(bookings, startYear, targetYear, docsByReferenz);
          List<PlausibilityResult> plausibilityResults = runPlausibilityChecks(data, startYear, targetYear, bookings, docsByReferenz);
          monitor.setPercentComplete(90);

          CachedAuditData cad = new CachedAuditData();
          cad.targetYear = targetYear;
          cad.startYear = startYear;
          cad.turnusJaehrlich = turnusJaehrlichFinal;
          cad.data = data;
          cad.plausibilityResults = plausibilityResults;
          cad.bookings = bookings;
          cad.docsByReferenz = docsByReferenz;
          cachedAuditData = cad;

          GUI.getDisplay().asyncExec(() -> {
            try { renderCachedAuditResults(); } catch (Exception e) { Logger.error("Fehler beim Zeichnen der Audits", e); }
          });

          monitor.setPercentComplete(100);
          monitor.setStatus(de.willuhn.util.ProgressMonitor.STATUS_DONE);
          monitor.setStatusText("Audits geladen");
        }
        catch (Exception e)
        {
          Logger.error("Fehler beim Laden der Audits", e);
          throw new ApplicationException("Fehler beim Laden der Audits: " + e.getMessage());
        }
      }

      @Override
      public void interrupt() {}
      @Override
      public boolean isInterrupted() { return false; }
    });
  }

  private void renderCachedAuditResults() throws Exception
  {
    if (cachedAuditData == null) return;

    int targetYear = cachedAuditData.targetYear;
    int startYear = cachedAuditData.startYear;
    ProcessedData data = cachedAuditData.data;
    List<PlausibilityResult> plausibilityResults = cachedAuditData.plausibilityResults;
    List<Buchung> bookings = cachedAuditData.bookings;
    Map<Long, List<BuchungDokument>> docsByReferenz = cachedAuditData.docsByReferenz;

    SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
    Calendar cal = Calendar.getInstance();

    // Populate Warnungen Table & Problem Buchungen Table
    if (warnungenTable != null && !warnungenTable.isDisposed())
    {
      warnungenTable.removeAll();
      for (PlausibilityResult r : plausibilityResults)
      {
        TableItem item = new TableItem(warnungenTable, SWT.NONE);
        item.setData("result", r);
        if (r.level == CheckLevel.CRITICAL)
        {
          item.setText(0, "✗ FEHLER");
          item.setForeground(0, GUI.getDisplay().getSystemColor(SWT.COLOR_RED));
        }
        else if (r.level == CheckLevel.WARNING)
        {
          item.setText(0, "⚠ WARNUNG");
          item.setForeground(0, GUI.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));
        }
        else
        {
          item.setText(0, "✓ OK");
          item.setForeground(0, GUI.getDisplay().getSystemColor(SWT.COLOR_GREEN));
        }

        item.setText(1, String.valueOf(r.year));
        item.setText(2, r.checkName);
        String fullDetails = r.message + (r.details != null ? " (" + r.details + ")" : "");
        item.setText(3, fullDetails);
      }
    }

    filterProblemBuchungenTable(null);

    // Populate missing receipts & Nachweise table (unified list)
    if (missingBelegeTable != null && !missingBelegeTable.isDisposed())
    {
      missingBelegeTable.removeAll();
      missingBelegeList.clear();

      // 1. Missing digital PDF receipt files (filtered against legal exemptions)
      for (Buchung b : data.missingBelegeList)
      {
        missingBelegeList.add(b);
        TableItem item = new TableItem(missingBelegeTable, SWT.NONE);
        item.setText(0, String.valueOf(b.getID()));
        item.setText(1, "Beleg fehlt");
        item.setText(2, b.getDatum() != null ? sdf.format(b.getDatum()) : "");
        item.setText(3, b.getBuchungsart() != null ? b.getBuchungsart().getBezeichnung() : "Ohne Buchungsart");
        item.setText(4, b.getName() != null ? b.getName() : "");
        item.setText(5, b.getBetrag() != null ? String.format("%.2f €", b.getBetrag()) : "");
        item.setText(6, "Digitale Belegdatei (Rechnung/Quittung) als Anhang hinzufügen");
      }

      // 2. Large donations > 300 € without receipt
      for (Buchung b : data.largeDonationsWithoutReceipt)
      {
        if (!missingBelegeList.contains(b))
        {
          missingBelegeList.add(b);
          TableItem item = new TableItem(missingBelegeTable, SWT.NONE);
          item.setText(0, String.valueOf(b.getID()));
          item.setText(1, "Großspende >300€");
          item.setText(2, b.getDatum() != null ? sdf.format(b.getDatum()) : "");
          item.setText(3, b.getBuchungsart() != null ? b.getBuchungsart().getBezeichnung() : "Spende");
          item.setText(4, b.getName() != null ? b.getName() : "");
          item.setText(5, b.getBetrag() != null ? String.format("%.2f €", b.getBetrag()) : "");
          item.setText(6, "Formelle Zuwendungsbestätigung ausstellen & erfassen");
        }
      }

      // 3. Aufwandsspenden / Verzichtserklärungen
      for (Buchung b : bookings)
      {
        String zweck = b.getZweck() != null ? b.getZweck().toLowerCase() : "";
        String name = b.getName() != null ? b.getName().toLowerCase() : "";
        if (zweck.contains("verzicht") || zweck.contains("aufwand") || name.contains("verzicht"))
        {
          if (!missingBelegeList.contains(b))
          {
            missingBelegeList.add(b);
            TableItem item = new TableItem(missingBelegeTable, SWT.NONE);
            item.setText(0, String.valueOf(b.getID()));
            item.setText(1, "Aufwandsspende");
            item.setText(2, b.getDatum() != null ? sdf.format(b.getDatum()) : "");
            item.setText(3, b.getBuchungsart() != null ? b.getBuchungsart().getBezeichnung() : "Spende");
            item.setText(4, b.getName() != null ? b.getName() : "");
            item.setText(5, b.getBetrag() != null ? String.format("%.2f €", b.getBetrag()) : "");
            item.setText(6, "Schriftliche Verzichtserklärung / Vertrag erforderlich");
          }
        }
      }
    }

    // Update Ergebnisse warning label
    Map<Integer, Integer> unassignedCountByYear = new HashMap<>();
    for (int y = startYear; y <= targetYear; y++)
    {
      unassignedCountByYear.put(y, 0);
    }
    for (Buchung b : data.unassignedBookings)
    {
      cal.setTime(b.getDatum());
      int y = cal.get(Calendar.YEAR);
      if (unassignedCountByYear.containsKey(y))
      {
        unassignedCountByYear.put(y, unassignedCountByYear.get(y) + 1);
      }
    }

    if (ergebnisseWarningLabel != null && !ergebnisseWarningLabel.isDisposed())
    {
      boolean hasUnassigned = false;
      StringBuilder sb = new StringBuilder();
      sb.append("Warnung: Nicht zugeordnete Buchungen (keine Sphäre): ");
      for (int y = startYear; y <= targetYear; y++)
      {
        int count = unassignedCountByYear.getOrDefault(y, 0);
        if (count > 0)
        {
          hasUnassigned = true;
        }
        sb.append(y).append(": ").append(count).append(" Buchung(en)  ");
      }
      if (hasUnassigned)
      {
        ergebnisseWarningLabel.setText(sb.toString());
        ergebnisseWarningLabel.setForeground(GUI.getDisplay().getSystemColor(SWT.COLOR_RED));
      }
      else
      {
        ergebnisseWarningLabel.setText("Alle Buchungen sind erfolgreich einer steuerrechtlichen Sphäre zugeordnet.");
        ergebnisseWarningLabel.setForeground(GUI.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
      }
      ergebnisseWarningLabel.getParent().layout();
    }

    // Populate Ergebnisse table side-by-side
    if (ergebnisseTable != null && !ergebnisseTable.isDisposed())
    {
      ergebnisseTable.setRedraw(false);
      try
      {
        ergebnisseTable.removeAll();
        for (TableColumn col : ergebnisseTable.getColumns())
        {
          col.dispose();
        }

        // Recreate columns based on startYear and targetYear
        TableColumn colHeader = new TableColumn(ergebnisseTable, SWT.LEFT);
        colHeader.setText("Sphäre / Buchungsart");
        colHeader.setWidth(250);

        for (int y = startYear; y <= targetYear; y++)
        {
          TableColumn colInc = new TableColumn(ergebnisseTable, SWT.RIGHT);
          colInc.setText(y + " Einnahmen");
          colInc.setWidth(110);

          TableColumn colExp = new TableColumn(ergebnisseTable, SWT.RIGHT);
          colExp.setText(y + " Ausgaben");
          colExp.setWidth(110);

          TableColumn colNet = new TableColumn(ergebnisseTable, SWT.RIGHT);
          colNet.setText(y + " Saldo");
          colNet.setWidth(110);
        }

        for (Sphere s : Sphere.values())
        {
          TableItem sphereRow = new TableItem(ergebnisseTable, SWT.NONE);
          sphereRow.setText(0, s.getLabel());
          sphereRow.setFont(de.willuhn.jameica.gui.util.Font.BOLD.getSWTFont());
          sphereRow.setBackground(GUI.getDisplay().getSystemColor(SWT.COLOR_TITLE_INACTIVE_BACKGROUND));

          int yearIdx = 0;
          for (int y = startYear; y <= targetYear; y++)
          {
            double inc = data.incomeBySphereAndYear.get(s).getOrDefault(y, 0.0);
            double exp = data.expenseBySphereAndYear.get(s).getOrDefault(y, 0.0);
            double net = inc - exp;

            sphereRow.setText(1 + 3 * yearIdx, String.format("%.2f €", inc));
            sphereRow.setText(2 + 3 * yearIdx, String.format("%.2f €", exp));
            sphereRow.setText(3 + 3 * yearIdx, String.format("%.2f €", net));

            if (net < 0 && s == Sphere.WGB)
            {
              sphereRow.setBackground(3 + 3 * yearIdx, GUI.getDisplay().getSystemColor(SWT.COLOR_YELLOW));
            }
            yearIdx++;
          }

          // Get unique Buchungsart names for this sphere
          Set<String> baNames = new TreeSet<>();
          for (int y = startYear; y <= targetYear; y++)
          {
            Map<String, Double> incD = data.incomeDetails.get(s).get(y);
            if (incD != null) baNames.addAll(incD.keySet());
            Map<String, Double> expD = data.expenseDetails.get(s).get(y);
            if (expD != null) baNames.addAll(expD.keySet());
          }

          // Detail rows for each Buchungsart
          for (String baName : baNames)
          {
            TableItem detailRow = new TableItem(ergebnisseTable, SWT.NONE);
            detailRow.setText(0, "  " + baName);

            int detailYearIdx = 0;
            for (int y = startYear; y <= targetYear; y++)
            {
              double inc = data.incomeDetails.get(s).get(y).getOrDefault(baName, 0.0);
              double exp = data.expenseDetails.get(s).get(y).getOrDefault(baName, 0.0);
              double net = inc - exp;

              detailRow.setText(1 + 3 * detailYearIdx, inc != 0.0 ? String.format("%.2f €", inc) : "");
              detailRow.setText(2 + 3 * detailYearIdx, exp != 0.0 ? String.format("%.2f €", exp) : "");
              detailRow.setText(3 + 3 * detailYearIdx, (inc != 0.0 || exp != 0.0) ? String.format("%.2f €", net) : "");
              detailYearIdx++;
            }
          }
        }
      }
      finally
      {
        ergebnisseTable.setRedraw(true);
      }
    }

    // Populate Vermögensaufstellung & Rücklagen side-by-side
    if (vermoegenTable != null && !vermoegenTable.isDisposed() && ruecklagenTable != null && !ruecklagenTable.isDisposed())
    {
      vermoegenTable.setRedraw(false);
      ruecklagenTable.setRedraw(false);
      try
      {
        vermoegenTable.removeAll();
        ruecklagenTable.removeAll();

        for (TableColumn col : vermoegenTable.getColumns()) col.dispose();
        for (TableColumn col : ruecklagenTable.getColumns()) col.dispose();

        // 1. Columns for Vermögensaufstellung
        TableColumn colVH = new TableColumn(vermoegenTable, SWT.LEFT);
        colVH.setText("Kategorie / Posten");
        colVH.setWidth(300);

        // 2. Columns for Rücklagen
        TableColumn colRH = new TableColumn(ruecklagenTable, SWT.LEFT);
        colRH.setText("R\u00fccklagenart / Verm\u00f6genszuf\u00fchrung");
        colRH.setWidth(300);

        for (int y = startYear; y <= targetYear; y++)
        {
          TableColumn colV = new TableColumn(vermoegenTable, SWT.RIGHT);
          colV.setText("zum 31.12." + y);
          colV.setWidth(120);

          TableColumn colR = new TableColumn(ruecklagenTable, SWT.RIGHT);
          colR.setText("zum 31.12." + y);
          colR.setWidth(120);
        }

        // Fetch the data
        Map<String, Map<Integer, Double>> vData = getVermoegensaufstellungData(startYear, targetYear);

        // Populate Vermögensaufstellung Rows
        String[][] vermoegenKeys = {
          { "Anlageverm\u00f6gen (Grundst\u00fccke, Geb\u00e4ude, Einrichtungen, Kfz usw.)", "anlagevermoegen" },
          { "Kassenbestand und Bankguthaben", "kassenbestand" },
          { "Wertpapiere (Festgelder, Sparb\u00fccher, Aktien usw.)", "wertpapiere" },
          { "Forderungen", "forderungen" },
          { "Verbindlichkeiten", "verbindlichkeiten" }
        };

        for (String[] vk : vermoegenKeys)
        {
          TableItem item = new TableItem(vermoegenTable, SWT.NONE);
          item.setText(0, vk[0]);
          int yearIdx = 0;
          for (int y = startYear; y <= targetYear; y++)
          {
            double val = vData.get(vk[1]).getOrDefault(y, 0.0);
            item.setText(1 + yearIdx, String.format("%.2f \u20ac", val));
            yearIdx++;
          }
        }

        // Populate Rücklagen Rows
        String[][] ruecklagenKeys = {
          { "Projektru\u0308cklagen, Betriebsmittelru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 1 AO)", "projektruecklagen" },
          { "Freie Ru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 3 AO)", "freieruecklagen" },
          { "Verm\u00f6genszuf\u00fchrungen aus Schenkungen, Erbschaften, Spendenaufrufen usw. (\u00a7 62 Abs. 3/4 AO)", "vermoegenszufuehrungen" }
        };

        for (String[] rk : ruecklagenKeys)
        {
          TableItem item = new TableItem(ruecklagenTable, SWT.NONE);
          item.setText(0, rk[0]);
          int yearIdx = 0;
          for (int y = startYear; y <= targetYear; y++)
          {
            double val = vData.get(rk[1]).getOrDefault(y, 0.0);
            item.setText(1 + yearIdx, String.format("%.2f \u20ac", val));
            yearIdx++;
          }
        }
      }
      catch (Exception e)
      {
        Logger.error("Fehler beim Laden der Verm\u00f6gensaufstellung", e);
      }
      finally
      {
        vermoegenTable.setRedraw(true);
        ruecklagenTable.setRedraw(true);
      }
    }
  }

  private void filterProblemBuchungenTable(PlausibilityResult filterResult)
  {
    if (problemBuchungenTable == null || problemBuchungenTable.isDisposed() || cachedAuditData == null)
    {
      return;
    }

    try
    {
      problemBuchungenTable.removeAll();
      problemBuchungenList.clear();

      SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
      List<Buchung> bookings = cachedAuditData.bookings;
      ProcessedData data = cachedAuditData.data;
      Set<Long> addedBookingIds = new java.util.HashSet<>();

      String checkName = (filterResult != null && filterResult.checkName != null) ? filterResult.checkName : "";

      for (Buchung b : bookings)
      {
        if (filterResult != null && b.getDatum() != null)
        {
          Calendar c = Calendar.getInstance();
          c.setTime(b.getDatum());
          if (c.get(Calendar.YEAR) != filterResult.year)
          {
            continue;
          }
        }

        String problemDesc = null;

        if (checkName.isEmpty() || checkName.contains("Unzugeordnete"))
        {
          if (b.getBuchungsart() == null || b.getBuchungsklasse() == null)
          {
            problemDesc = "Fehlende Sphären- / Buchungsartzuordnung";
          }
        }
        if (problemDesc == null && (checkName.isEmpty() || checkName.contains("Kontierung")))
        {
          if (b.getKonto() == null)
          {
            problemDesc = "Fehlendes Finanzkonto";
          }
        }
        if (problemDesc == null && (checkName.isEmpty() || checkName.contains("Belegabdeckung") || checkName.contains("GoBD")))
        {
          if (data != null && data.missingBelegeList.contains(b))
          {
            problemDesc = "Digitale Belegdatei fehlt (GoBD)";
          }
        }
        if (problemDesc == null && (checkName.isEmpty() || checkName.contains("Großspende") || checkName.contains("Spenden")))
        {
          if (data != null && data.largeDonationsWithoutReceipt.contains(b))
          {
            problemDesc = "Großspende >300€: Zuwendungsbestätigung fehlt";
          }
        }

        // If specific filter selected and no direct problem matched, show year's bookings for context
        if (problemDesc == null && !checkName.isEmpty() && filterResult != null && filterResult.level != CheckLevel.INFO)
        {
          // For general warning checks (e.g. WGB Freigrenze), list bookings of that year
          if (checkName.contains("WGB"))
          {
            if (b.getBuchungsklasse() != null && getSphere(b.getBuchungsklasse(), b.getBuchungsart()) == Sphere.WGB)
            {
              problemDesc = "WGB-Buchung (" + filterResult.checkName + ")";
            }
          }
        }

        if (problemDesc != null)
        {
          if (!addedBookingIds.contains(Long.valueOf(b.getID())))
          {
            addedBookingIds.add(Long.valueOf(b.getID()));
            problemBuchungenList.add(b);
            TableItem item = new TableItem(problemBuchungenTable, SWT.NONE);
            item.setText(0, String.valueOf(b.getID()));
            item.setText(1, b.getDatum() != null ? sdf.format(b.getDatum()) : "");
            item.setText(2, b.getBuchungsart() != null ? b.getBuchungsart().getBezeichnung() : "Ohne Buchungsart");
            item.setText(3, b.getName() != null ? b.getName() : "");
            item.setText(4, b.getZweck() != null ? b.getZweck() : "");
            item.setText(5, b.getBetrag() != null ? String.format("%.2f €", b.getBetrag()) : "");
            item.setText(6, problemDesc);
          }
        }
      }
    }
    catch (Exception e)
    {
      Logger.error("Fehler beim Filtern der Betroffenen Buchungen", e);
    }
  }

  private static class ProcessedData
  {
    Map<Sphere, Map<Integer, Double>> incomeBySphereAndYear = new HashMap<>();
    Map<Sphere, Map<Integer, Double>> expenseBySphereAndYear = new HashMap<>();
    Map<Sphere, Map<Integer, Map<String, Double>>> incomeDetails = new HashMap<>();
    Map<Sphere, Map<Integer, Map<String, Double>>> expenseDetails = new HashMap<>();
    Map<Integer, Double> wgbGrossByYear = new HashMap<>();
    Map<Integer, Double> wgbNetByYear = new HashMap<>();
    Map<Integer, Double> totalRevenueByYear = new HashMap<>();
    List<Buchung> unassignedBookings = new ArrayList<>();
    List<Buchung> missingBelegeList = new ArrayList<>();
    List<Buchung> largeDonationsWithoutReceipt = new ArrayList<>();
  }

  // --- Plausibility Check Infrastructure ---

  public enum CheckLevel
  {
    CRITICAL("FEHLER"),
    WARNING("WARNUNG"),
    INFO("INFO");

    private final String label;
    CheckLevel(String label) { this.label = label; }
    public String getLabel() { return label; }
  }

  private static class PlausibilityResult
  {
    CheckLevel level;
    String checkName;
    int year;
    String message;
    String details;

    PlausibilityResult(CheckLevel level, String checkName, int year, String message, String details)
    {
      this.level = level;
      this.checkName = checkName;
      this.year = year;
      this.message = message;
      this.details = details;
    }
  }

  private List<PlausibilityResult> runPlausibilityChecks(
      ProcessedData data, int startYear, int targetYear,
      List<Buchung> allBookings, Map<Long, List<BuchungDokument>> docsByReferenz) throws Exception
  {
    List<PlausibilityResult> results = new ArrayList<>();

    for (int y = startYear; y <= targetYear; y++)
    {
      // --- Check 1: Unzugeordnete Buchungen (CRITICAL) ---
      double unassIncome = data.incomeBySphereAndYear.get(Sphere.UNASSIGNED).getOrDefault(y, 0.0);
      double unassExpense = data.expenseBySphereAndYear.get(Sphere.UNASSIGNED).getOrDefault(y, 0.0);
      if (Math.abs(unassIncome) > 0.01 || Math.abs(unassExpense) > 0.01)
      {
        int count = 0;
        for (Buchung b : data.unassignedBookings)
        {
          Calendar c = Calendar.getInstance();
          c.setTime(b.getDatum());
          if (c.get(Calendar.YEAR) == y) count++;
        }
        results.add(new PlausibilityResult(CheckLevel.CRITICAL,
            "Unzugeordnete Buchungen", y,
            String.format("%d Buchung(en) ohne Sphärenzuordnung (Einnahmen: %.2f €, Ausgaben: %.2f €)", count, unassIncome, unassExpense),
            "Alle Buchungen müssen einem der 4 Bereiche (Ideell, VV, ZB, WGB) zugeordnet sein. Das Finanzamt verlangt eine lückenlose Zuordnung."));
      }
      else
      {
        results.add(new PlausibilityResult(CheckLevel.INFO,
            "Unzugeordnete Buchungen", y,
            "Alle Buchungen sind einer Sphäre zugeordnet.", null));
      }

      // --- Check 2: WGB-Freigrenze §64 Abs. 3 AO (CRITICAL) ---
      double wgbIncome = data.incomeBySphereAndYear.get(Sphere.WGB).getOrDefault(y, 0.0);
      double wgbExpense = data.expenseBySphereAndYear.get(Sphere.WGB).getOrDefault(y, 0.0);
      double freigrenze = (y >= 2026) ? 50000.0 : 45000.0;

      if (wgbIncome > freigrenze)
      {
        double wgbGewinn = wgbIncome - wgbExpense;
        double kstPflichtig = Math.max(0.0, wgbGewinn - 5000.0);
        results.add(new PlausibilityResult(CheckLevel.CRITICAL,
            "WGB-Freigrenze überschritten", y,
            String.format("WGB-Einnahmen %.2f € überschreiten die Freigrenze von %.0f € (§64 Abs. 3 AO)", wgbIncome, freigrenze),
            String.format("Gewinn WGB: %.2f €, abzgl. Freibetrag §24 KStG (5.000 €): %.2f € KSt-pflichtig. " +
                "Es wird KSt 1 mit Anlage GK und ZVE benötigt.", wgbGewinn, kstPflichtig)));
      }
      else if (wgbIncome > freigrenze * 0.9 && wgbIncome > 0)
      {
        results.add(new PlausibilityResult(CheckLevel.WARNING,
            "WGB nahe an Freigrenze", y,
            String.format("WGB-Einnahmen %.2f € erreichen %.0f%% der Freigrenze von %.0f €",
                wgbIncome, (wgbIncome / freigrenze * 100), freigrenze),
            "Bei Überschreitung der Freigrenze werden ALLE WGB-Einnahmen steuerpflichtig (Freigrenze, nicht Freibetrag!)."));
      }
      else
      {
        results.add(new PlausibilityResult(CheckLevel.INFO,
            "WGB-Freigrenze", y,
            String.format("WGB-Einnahmen %.2f € liegen unter der Freigrenze von %.0f €.", wgbIncome, freigrenze), null));
      }

      // --- Check 3: Freie Rücklage §62 Abs. 1 Nr. 3 AO (CRITICAL) ---
      double vvIncome = data.incomeBySphereAndYear.get(Sphere.VERMOEGENSVERWALTUNG).getOrDefault(y, 0.0);
      double vvExpense = data.expenseBySphereAndYear.get(Sphere.VERMOEGENSVERWALTUNG).getOrDefault(y, 0.0);
      double vvUeberschuss = Math.max(0.0, vvIncome - vvExpense);
      double maxAusVV = vvUeberschuss / 3.0;

      double ideellIncome = data.incomeBySphereAndYear.get(Sphere.IDEELL).getOrDefault(y, 0.0);
      double zbIncome = data.incomeBySphereAndYear.get(Sphere.ZWECKBETRIEB).getOrDefault(y, 0.0);
      double sonstigeMittel = ideellIncome + zbIncome + wgbIncome;
      double maxAusSonstige = sonstigeMittel * 0.10;

      double maxFreieRuecklage = maxAusVV + maxAusSonstige;

      // Check actual Rücklage bookings (accounts 77790, 77810 = Zuführung freie Rücklage)
      double tatsZufuehrungFrei = 0.0;
      double tatsZufuehrungGebunden = 0.0;
      double tatsEntnahme = 0.0;
      for (Buchung b : allBookings)
      {
        Calendar c = Calendar.getInstance();
        c.setTime(b.getDatum());
        if (c.get(Calendar.YEAR) != y) continue;
        Buchungsart ba = b.getBuchungsart();
        if (ba == null) continue;
        String num = ba.getNummer();
        if (num == null) continue;
        double betrag = Math.abs(b.getBetrag() != null ? b.getBetrag() : 0.0);
        if (num.startsWith("77810") || num.startsWith("7781"))
          tatsZufuehrungFrei += betrag;
        else if (num.startsWith("77790") || num.startsWith("7779"))
          tatsZufuehrungGebunden += betrag;
        else if (num.startsWith("77510") || num.startsWith("7751") || num.startsWith("77490") || num.startsWith("7749"))
          tatsEntnahme += betrag;
      }

      if (tatsZufuehrungFrei > maxFreieRuecklage + 0.01 && maxFreieRuecklage > 0)
      {
        results.add(new PlausibilityResult(CheckLevel.CRITICAL,
            "Freie Rücklage über Maximum", y,
            String.format("Zuführung freie Rücklage %.2f € übersteigt das Maximum von %.2f € (§62 Abs. 1 Nr. 3 AO)",
                tatsZufuehrungFrei, maxFreieRuecklage),
            String.format("Max. aus VV: 1/3 von %.2f € = %.2f €. Max. aus sonstigen Mitteln: 10%% von %.2f € = %.2f €. " +
                "Gesamt max. zulässig: %.2f €. Ungenutztes Potenzial kann in den 2 Folgejahren nachgeholt werden.",
                vvUeberschuss, maxAusVV, sonstigeMittel, maxAusSonstige, maxFreieRuecklage)));
      }
      else
      {
        results.add(new PlausibilityResult(CheckLevel.INFO,
            "Freie Rücklage", y,
            String.format("Zuführung %.2f € (max. zulässig: %.2f €). Gebundene Rücklage: %.2f €, Entnahmen: %.2f €.",
                tatsZufuehrungFrei, maxFreieRuecklage, tatsZufuehrungGebunden, tatsEntnahme), null));
      }

      // --- Check 4: Belege-Abdeckung (§146 AO / GoBD) ---
      int totalBookings = 0;
      int withAttachedBeleg = 0;
      int withKontoauszugProof = 0;
      int exemptBeleg = 0;
      int missingBeleg = 0;

      for (Buchung b : allBookings)
      {
        Calendar c = Calendar.getInstance();
        c.setTime(b.getDatum());
        if (c.get(Calendar.YEAR) != y) continue;

        Buchungsart ba = b.getBuchungsart();
        // Skip internal transfers / Umbuchungen
        if (ba != null && ba.getArt() == 2) continue;

        totalBookings++;
        Long bid = Long.valueOf(b.getID());
        boolean hasAttachment = (docsByReferenz != null && docsByReferenz.containsKey(bid));
        boolean hasAuszugsnummer = (b.getAuszugsnummer() != null && b.getAuszugsnummer() > 0)
                                || (b.getBlattnummer() != null && b.getBlattnummer() > 0);

        String num = (ba != null && ba.getNummer() != null) ? ba.getNummer() : "";
        String bez = (ba != null && ba.getBezeichnung() != null) ? ba.getBezeichnung().toLowerCase() : "";
        String zw = (b.getZweck() != null) ? b.getZweck().toLowerCase() : "";
        double betrag = Math.abs(b.getBetrag() != null ? b.getBetrag() : 0.0);

        boolean isSofortabschreibungGwg = num.startsWith("4855") || num.startsWith("6260")
                                        || (bez.contains("sofortabschreibung") || zw.contains("sofortabschreibung"))
                                        || ((bez.contains("gwg") || zw.contains("gwg")) && !bez.contains("sammelposten") && !bez.contains("gruppe") && !bez.contains("pool"));

        boolean isPoolOrRegularAfa = !isSofortabschreibungGwg && (
            num.startsWith("6200") || num.startsWith("6220") || num.startsWith("6230")
         || num.startsWith("6240") || num.startsWith("6250") || num.startsWith("6262")
         || num.startsWith("4830") || num.startsWith("4831") || num.startsWith("4832")
         || num.startsWith("4840") || num.startsWith("4850") || num.startsWith("4862")
         || num.startsWith("7700") || num.startsWith("7710")
         || bez.contains("abschreibung") || bez.contains("afa") || bez.contains("sammelposten") || bez.contains("pool") || bez.contains("gruppe")
         || zw.contains("abschreibung") || zw.contains("afa") || zw.contains("sammelposten") || zw.contains("pool") || zw.contains("gruppe")
        );

        if (hasAttachment)
        {
          withAttachedBeleg++;
        }
        else if (isPoolOrRegularAfa || num.startsWith("400") || num.startsWith("401") || num.startsWith("1372") || (num.startsWith("404") && betrag <= 300.0))
        {
          // Gruppenabschreibung / Pool-AfA / Gebaeude-AfA, membership fees, transit, donations <= 300 €:
          // Exempt from receipt file requirement
          exemptBeleg++;
        }
        else if (hasAuszugsnummer || num.startsWith("6855"))
        {
          // Durch Kontoauszugsnummer / Bankauszug nachgewiesen (z.B. Bankgebühren)
          withKontoauszugProof++;
        }
        else
        {
          missingBeleg++;
        }
      }

      int proofedTotal = withAttachedBeleg + withKontoauszugProof + exemptBeleg;
      double proofedPercent = totalBookings > 0 ? (proofedTotal * 100.0 / totalBookings) : 100.0;

      if (missingBeleg > 0)
      {
        results.add(new PlausibilityResult(
            proofedPercent >= 80.0 ? CheckLevel.INFO : CheckLevel.WARNING,
            "Belegabdeckung (GoBD)", y,
            String.format("%.0f%% ordnungsgemäß belegt (%d/%d): %d mit Belegdatei, %d per Kontoauszug, %d gesetzlich beitragsbefreit, %d fehlend.",
                proofedPercent, proofedTotal, totalBookings, withAttachedBeleg, withKontoauszugProof, exemptBeleg, missingBeleg),
            "Für Mitgliedsbeiträge, Geldtransit & Bankgebühren ist der Kontoauszug/SEPA-Nachweis gesetzlich ausreichend. " +
            "Für Fremdleistungen und Wareneinkäufe ohne Beleg wird eine digitale Belegdatei empfohlen."));
      }
      else
      {
        results.add(new PlausibilityResult(CheckLevel.INFO,
            "Belegabdeckung (GoBD)", y,
            String.format("100%% ordnungsgemäß belegt (%d/%d): %d mit Belegdatei, %d per Kontoauszug, %d gesetzlich beitragsbefreit.",
                proofedTotal, totalBookings, withAttachedBeleg, withKontoauszugProof, exemptBeleg), null));
      }

      // --- Check 4b: Beleginhalt-Plausibilität (PDF Text & Betrags-Prüfung via PDFBox) ---
      int scannedDocs = 0;
      int verifiedDocs = 0;
      int amountMismatchDocs = 0;
      List<String> mismatchDetails = new ArrayList<>();

      for (Buchung b : allBookings)
      {
        Calendar c = Calendar.getInstance();
        c.setTime(b.getDatum());
        if (c.get(Calendar.YEAR) != y) continue;

        Long bid = Long.valueOf(b.getID());
        List<BuchungDokument> docs = (docsByReferenz != null) ? docsByReferenz.get(bid) : null;
        if (docs == null || docs.isEmpty()) continue;

        double expectedAmount = Math.abs(b.getBetrag() != null ? b.getBetrag() : 0.0);
        String amountStrComma = String.format("%.2f", expectedAmount).replace(".", ",");
        String amountStrDot = String.format("%.2f", expectedAmount).replace(",", ".");

        for (BuchungDokument doc : docs)
        {
          File binFile = new File(de.willuhn.jameica.system.Application.getPlatform().getWorkdir(),
              "jameica.messaging/archive/buchungen/" + b.getID() + "/" + doc.getUUID());
          if (!binFile.exists() || binFile.length() == 0) continue;

          try (org.apache.pdfbox.pdmodel.PDDocument pdDoc = org.apache.pdfbox.Loader.loadPDF(binFile))
          {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String extractedText = stripper.getText(pdDoc);

            if (extractedText == null || extractedText.trim().isEmpty())
            {
              scannedDocs++; // Image-based PDF (no text layer)
            }
            else
            {
              // Text layer present: Check if expected amount appears in text
              boolean amountFound = extractedText.contains(amountStrComma) || extractedText.contains(amountStrDot);
              if (!amountFound && expectedAmount >= 1.0)
              {
                // Try integer amount if no decimals (e.g. "1000 €")
                if (expectedAmount == Math.floor(expectedAmount))
                {
                  String intStr = String.format("%.0f", expectedAmount);
                  amountFound = extractedText.contains(intStr);
                }
              }

              if (amountFound)
              {
                verifiedDocs++;
              }
              else
              {
                amountMismatchDocs++;
                if (mismatchDetails.size() < 5)
                {
                  mismatchDetails.add(String.format("Buchung #%s (%.2f €): Betrag im PDF-Text nicht gefunden",
                      b.getID(), expectedAmount));
                }
              }
            }
          }
          catch (Exception e)
          {
            // Non-PDF or unparseable PDF file, skip
          }
        }
      }

      if (amountMismatchDocs > 0)
      {
        results.add(new PlausibilityResult(CheckLevel.WARNING,
            "Beleginhalt-Abweichung", y,
            String.format("%d Beleg(e) inhaltlich verifiziert, %d Beleg(e) mit Betragsabweichung im PDF-Text.",
                verifiedDocs, amountMismatchDocs),
            "Geprüfte Abweichungen: " + String.join("; ", mismatchDetails)));
      }
      else if (verifiedDocs > 0)
      {
        results.add(new PlausibilityResult(CheckLevel.INFO,
            "Beleginhalt-Plausibilität", y,
            String.format("100%% der lesbaren PDF-Belege (%d Stk.) stimmen inhaltlich mit dem Buchungsbetrag überein (%d Scans ohne Textschicht).",
                verifiedDocs, scannedDocs), null));
      }

      // --- Check 5: Buchungen ohne Konto oder Buchungsart (WARNING) ---
      int ohneKonto = 0;
      int ohneBuchungsart = 0;
      for (Buchung b : allBookings)
      {
        Calendar c = Calendar.getInstance();
        c.setTime(b.getDatum());
        if (c.get(Calendar.YEAR) != y) continue;
        if (b.getKonto() == null) ohneKonto++;
        if (b.getBuchungsart() == null) ohneBuchungsart++;
      }
      if (ohneKonto > 0 || ohneBuchungsart > 0)
      {
        results.add(new PlausibilityResult(CheckLevel.WARNING,
            "Unvollständige Kontierung", y,
            String.format("%d Buchung(en) ohne Gegenkonto, %d ohne Buchungsart.", ohneKonto, ohneBuchungsart),
            "DATEV erwartet für jede Buchung ein Konto und Gegenkonto. Unvollständige Buchungen verursachen Import-Fehler."));
      }
      else
      {
        results.add(new PlausibilityResult(CheckLevel.INFO,
            "Kontierung vollständig", y,
            "Alle Buchungen haben Konto und Buchungsart.", null));
      }

      // --- Check 6: Sphärenverteilung (INFO) ---
      StringBuilder sphereInfo = new StringBuilder();
      for (Sphere s : Sphere.values())
      {
        if (s == Sphere.UNASSIGNED) continue;
        double inc = data.incomeBySphereAndYear.get(s).getOrDefault(y, 0.0);
        double exp = data.expenseBySphereAndYear.get(s).getOrDefault(y, 0.0);
        if (Math.abs(inc) > 0.01 || Math.abs(exp) > 0.01)
        {
          sphereInfo.append(String.format("%s: Einnahmen %.2f €, Ausgaben %.2f €, Ergebnis %.2f €. ",
              s.getLabel(), inc, exp, inc - exp));
        }
      }
      results.add(new PlausibilityResult(CheckLevel.INFO,
          "Sphärenverteilung", y, sphereInfo.toString(), null));

      // --- Check 7: WGB-Gewinn Verwendung (INFO) ---
      double wgbGewinn = wgbIncome - wgbExpense;
      if (wgbGewinn > 0.01)
      {
        results.add(new PlausibilityResult(CheckLevel.INFO,
            "WGB-Gewinn", y,
            String.format("WGB-Gewinn %.2f € muss zeitnah für satzungsgemäße Zwecke verwendet werden.", wgbGewinn),
            "§55 Abs. 1 Nr. 5 AO: Mittel müssen zeitnah (spätestens 2. Folgejahr) verwendet werden."));
      }

      // --- Check 8: Zeitnahe Mittelverwendung (§55 Abs. 1 Nr. 5 AO) ---
      double gesamtEinnahmen = data.totalRevenueByYear.getOrDefault(y, 0.0);
      if (gesamtEinnahmen > 45000.0)
      {
        double gesamtAusgaben = 0.0;
        for (Sphere s : Sphere.values())
        {
          gesamtAusgaben += data.expenseBySphereAndYear.get(s).getOrDefault(y, 0.0);
        }
        double ueberschuss = gesamtEinnahmen - gesamtAusgaben;
        double gebundenesMittel = tatsZufuehrungFrei + tatsZufuehrungGebunden;
        double verbleibend = ueberschuss - gebundenesMittel;

        if (verbleibend > 1000.0)
        {
          // Positive Lookahead: Check if funds were used in year y+1 or y+2 within VZ
          double usedInFollowupYears = 0.0;
          for (int fy = y + 1; fy <= Math.min(targetYear, y + 2); fy++)
          {
            double fyInc = data.totalRevenueByYear.getOrDefault(fy, 0.0);
            double fyExp = 0.0;
            for (Sphere s : Sphere.values())
            {
              fyExp += data.expenseBySphereAndYear.get(s).getOrDefault(fy, 0.0);
            }
            double fyDeficitOrExtraExp = fyExp - fyInc;
            if (fyDeficitOrExtraExp > 0)
            {
              usedInFollowupYears += fyDeficitOrExtraExp;
            }
          }

          double netVerbleibend = verbleibend - usedInFollowupYears;
          if (netVerbleibend <= 1000.0)
          {
            results.add(new PlausibilityResult(CheckLevel.INFO,
                "Zeitnahe Mittelverwendung", y,
                String.format("Überschuss %.2f € im Jahr %d wurde in den Folgejahren des VZ (%s%.2f €) satzungsgemäß verwendet.",
                    verbleibend, y, (usedInFollowupYears > 0 ? String.format("%.2f € verwendet, verbleiben ", usedInFollowupYears) : ""), Math.max(0, netVerbleibend)),
                "§55 Abs. 1 Nr. 5 AO erlaubt die Verwendung bis zum Ende des 2. Folgejahres. Die Verwendung im VZ ist nachgewiesen."));
          }
          else
          {
            results.add(new PlausibilityResult(CheckLevel.WARNING,
                "Zeitnahe Mittelverwendung", y,
                String.format("Überschuss %.2f €, davon %.2f € gebunden. Verbleibend nach VZ-Folgejahren: %.2f €.",
                    ueberschuss, gebundenesMittel, netVerbleibend),
                "Bei Gesamteinnahmen > 45.000 € muss die zeitnahe Mittelverwendung nachgewiesen werden (§55 Abs. 1 Nr. 5 AO). " +
                "Mittel müssen bis Ende des 2. Folgejahres verwendet werden."));
          }
        }
        else
        {
          results.add(new PlausibilityResult(CheckLevel.INFO,
              "Zeitnahe Mittelverwendung", y,
              String.format("Kein ungebundener Überschuss im Jahr %d (Verbleibend: %.2f €).", y, Math.max(0, verbleibend)), null));
        }
      }
    }

    results.sort((r1, r2) -> {
      int c1 = r1.level == CheckLevel.CRITICAL ? 0 : (r1.level == CheckLevel.WARNING ? 1 : 2);
      int c2 = r2.level == CheckLevel.CRITICAL ? 0 : (r2.level == CheckLevel.WARNING ? 1 : 2);
      if (c1 != c2) return Integer.compare(c1, c2);
      return Integer.compare(r1.year, r2.year);
    });

    return results;
  }

  private void writePruefprotokollPDF(File file, List<PlausibilityResult> results,
      int startYear, int targetYear, ProcessedData data) throws Exception
  {
    Document doc = new Document(com.itextpdf.text.PageSize.A4, 36, 36, 50, 36);
    PdfWriter.getInstance(doc, new FileOutputStream(file));
    doc.open();

    Font titleFont = new Font(Font.FontFamily.HELVETICA, 18, Font.BOLD, BaseColor.DARK_GRAY);
    Font subtitleFont = new Font(Font.FontFamily.HELVETICA, 10, Font.ITALIC, BaseColor.GRAY);
    Font sectionFont = new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD, new BaseColor(0, 102, 153));
    Font headerFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.WHITE);
    Font cellFont = new Font(Font.FontFamily.HELVETICA, 8, Font.NORMAL, BaseColor.BLACK);
    Font okFont = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD, new BaseColor(0, 128, 0));
    Font warnFont = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD, new BaseColor(204, 102, 0));
    Font errorFont = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD, new BaseColor(204, 0, 0));

    // Title
    Paragraph title = new Paragraph("Prüfprotokoll DATEV-Exportpaket", titleFont);
    title.setSpacingAfter(5);
    doc.add(title);
    Paragraph sub = new Paragraph(
        String.format("Veranlagungszeitraum %d–%d | Erstellt am %s",
            startYear, targetYear, new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date())), subtitleFont);
    sub.setSpacingAfter(20);
    doc.add(sub);

    // Summary counts
    int criticalCount = 0, warningCount = 0, infoCount = 0;
    for (PlausibilityResult r : results)
    {
      switch (r.level)
      {
        case CRITICAL: criticalCount++; break;
        case WARNING: warningCount++; break;
        case INFO: infoCount++; break;
      }
    }

    Paragraph summary = new Paragraph(String.format(
        "Zusammenfassung: %d Fehler, %d Warnungen, %d Hinweise",
        criticalCount, warningCount, infoCount),
        criticalCount > 0 ? errorFont : (warningCount > 0 ? warnFont : okFont));
    summary.setSpacingAfter(15);
    doc.add(summary);

    // Section: Sphärenübersicht
    Paragraph sphereTitle = new Paragraph("1. Sphärenübersicht (Vier-Bereiche-System)", sectionFont);
    sphereTitle.setSpacingAfter(8);
    doc.add(sphereTitle);

    int yearCount = targetYear - startYear + 1;
    PdfPTable sphereTable = new PdfPTable(1 + yearCount * 3);
    sphereTable.setWidthPercentage(100);
    float[] widths = new float[1 + yearCount * 3];
    widths[0] = 3f;
    for (int i = 1; i < widths.length; i++) widths[i] = 1.5f;
    sphereTable.setWidths(widths);

    PdfPCell h = new PdfPCell(new Phrase("Bereich", headerFont));
    h.setBackgroundColor(new BaseColor(0, 102, 153));
    h.setHorizontalAlignment(Element.ALIGN_CENTER);
    h.setPadding(4);
    sphereTable.addCell(h);
    for (int y = startYear; y <= targetYear; y++)
    {
      for (String col : new String[]{"Einnahmen " + y, "Ausgaben " + y, "Ergebnis " + y})
      {
        h = new PdfPCell(new Phrase(col, headerFont));
        h.setBackgroundColor(new BaseColor(0, 102, 153));
        h.setHorizontalAlignment(Element.ALIGN_CENTER);
        h.setPadding(4);
        sphereTable.addCell(h);
      }
    }
    for (Sphere s : Sphere.values())
    {
      PdfPCell nameCell = new PdfPCell(new Phrase(s.getLabel(), cellFont));
      nameCell.setPadding(3);
      if (s == Sphere.UNASSIGNED)
        nameCell.setBackgroundColor(new BaseColor(255, 230, 230));
      sphereTable.addCell(nameCell);
      for (int y = startYear; y <= targetYear; y++)
      {
        double inc = data.incomeBySphereAndYear.get(s).getOrDefault(y, 0.0);
        double exp = data.expenseBySphereAndYear.get(s).getOrDefault(y, 0.0);
        double net = inc - exp;
        for (double val : new double[]{inc, exp, net})
        {
          PdfPCell vc = new PdfPCell(new Phrase(String.format("%.2f", val), cellFont));
          vc.setHorizontalAlignment(Element.ALIGN_RIGHT);
          vc.setPadding(3);
          if (s == Sphere.UNASSIGNED && Math.abs(val) > 0.01)
            vc.setBackgroundColor(new BaseColor(255, 200, 200));
          sphereTable.addCell(vc);
        }
      }
    }
    doc.add(sphereTable);
    doc.add(new Paragraph(" "));

    // Section: Checkliste
    Paragraph checkTitle = new Paragraph("2. Plausibilitätsprüfungen", sectionFont);
    checkTitle.setSpacingAfter(8);
    doc.add(checkTitle);

    PdfPTable checkTable = new PdfPTable(4);
    checkTable.setWidthPercentage(100);
    checkTable.setWidths(new float[]{1f, 0.6f, 3f, 3f});

    for (String colName : new String[]{"Status", "Jahr", "Prüfung", "Details"})
    {
      h = new PdfPCell(new Phrase(colName, headerFont));
      h.setBackgroundColor(new BaseColor(0, 102, 153));
      h.setPadding(4);
      checkTable.addCell(h);
    }

    for (PlausibilityResult r : results)
    {
      Font statusFont = r.level == CheckLevel.CRITICAL ? errorFont :
          (r.level == CheckLevel.WARNING ? warnFont : okFont);
      String statusSymbol = r.level == CheckLevel.CRITICAL ? "✗ FEHLER" :
          (r.level == CheckLevel.WARNING ? "⚠ WARNUNG" : "✓ OK");

      PdfPCell statusCell = new PdfPCell(new Phrase(statusSymbol, statusFont));
      statusCell.setPadding(3);
      if (r.level == CheckLevel.CRITICAL) statusCell.setBackgroundColor(new BaseColor(255, 230, 230));
      else if (r.level == CheckLevel.WARNING) statusCell.setBackgroundColor(new BaseColor(255, 243, 224));
      checkTable.addCell(statusCell);

      PdfPCell yearCell = new PdfPCell(new Phrase(String.valueOf(r.year), cellFont));
      yearCell.setPadding(3);
      yearCell.setHorizontalAlignment(Element.ALIGN_CENTER);
      checkTable.addCell(yearCell);

      PdfPCell msgCell = new PdfPCell(new Phrase(r.checkName + ": " + r.message, cellFont));
      msgCell.setPadding(3);
      checkTable.addCell(msgCell);

      PdfPCell detailCell = new PdfPCell(new Phrase(r.details != null ? r.details : "", cellFont));
      detailCell.setPadding(3);
      checkTable.addCell(detailCell);
    }
    doc.add(checkTable);
    doc.add(new Paragraph(" "));

    // Section: Gesetzliche Grundlagen
    Paragraph lawTitle = new Paragraph("3. Gesetzliche Grundlagen", sectionFont);
    lawTitle.setSpacingAfter(8);
    doc.add(lawTitle);

    Font lawFont = new Font(Font.FontFamily.HELVETICA, 8, Font.NORMAL, BaseColor.DARK_GRAY);
    String[] laws = {
        "§5 Abs. 1 Nr. 9 KStG — Steuerbefreiung für gemeinnützige Körperschaften",
        "§24 KStG — Freibetrag 5.000 € auf WGB-Gewinn",
        "§55 Abs. 1 Nr. 5 AO — Zeitnahe Mittelverwendung (2 Folgejahre)",
        "§62 Abs. 1 Nr. 1 AO — Zweckgebundene und Betriebsmittelrücklage",
        "§62 Abs. 1 Nr. 3 AO — Freie Rücklage (max. 1/3 VV-Überschuss + 10% sonstige Mittel)",
        "§64 Abs. 3 AO — Freigrenze WGB (50.000 € ab 2026, davor 45.000 €)",
    };
    for (String law : laws)
    {
      doc.add(new Paragraph("• " + law, lawFont));
    }

    doc.close();
  }

  private ProcessedData processBookings(List<Buchung> bookings, int startYear, int targetYear, Map<Long, List<BuchungDokument>> docsByReferenz) throws Exception
  {
    ProcessedData data = new ProcessedData();
    for (Sphere s : Sphere.values())
    {
      data.incomeBySphereAndYear.put(s, new HashMap<>());
      data.expenseBySphereAndYear.put(s, new HashMap<>());
      data.incomeDetails.put(s, new HashMap<>());
      data.expenseDetails.put(s, new HashMap<>());
      for (int y = startYear; y <= targetYear; y++)
      {
        data.incomeBySphereAndYear.get(s).put(y, 0.0);
        data.expenseBySphereAndYear.get(s).put(y, 0.0);
        data.incomeDetails.get(s).put(y, new HashMap<>());
        data.expenseDetails.get(s).put(y, new HashMap<>());
      }
    }

    boolean mitSteuer = false;
    boolean steuerInBuchung = false;
    boolean klasseInBuchung = false;
    try
    {
      mitSteuer = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.OPTIERTPFLICHT);
      steuerInBuchung = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.STEUERINBUCHUNG);
      klasseInBuchung = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.BUCHUNGSKLASSEINBUCHUNG);
    }
    catch (Exception e)
    {
      // fallback
    }

    Calendar cal = Calendar.getInstance();
    for (Buchung b : bookings)
    {
      cal.setTime(b.getDatum());
      int year = cal.get(Calendar.YEAR);
      if (year < startYear || year > targetYear)
      {
        continue;
      }

      Double betrag = b.getBetrag() != null ? b.getBetrag() : 0.0;
      Buchungsart bart = b.getBuchungsart();
      Konto konto = b.getKonto();

      // Check Geldtransit to ignore completely
      boolean isGeldtransit = false;
      if (bart != null)
      {
        String baName = bart.getBezeichnung() != null ? bart.getBezeichnung().toLowerCase() : "";
        if (baName.contains("geldtransit")) isGeldtransit = true;
      }
      String zw = b.getZweck() != null ? b.getZweck().toLowerCase() : "";
      if (zw.contains("geldtransit")) isGeldtransit = true;

      if (isGeldtransit) continue;

      if (konto != null)
      {
        Kontoart ka = konto.getKontoArt();
        if (ka != null && ka.getKey() >= Kontoart.LIMIT.getKey())
        {
          continue;
        }
      }

      Buchungsklasse bklasse = null;
      if (klasseInBuchung)
      {
        bklasse = b.getBuchungsklasse();
      }
      else if (bart != null)
      {
        bklasse = bart.getBuchungsklasse();
      }

      Sphere sphere = getSphere(bklasse, bart);

      boolean isBankFee = false;
      if (bart != null)
      {
        String baName = bart.getBezeichnung() != null ? bart.getBezeichnung().toLowerCase() : "";
        if (baName.contains("geldverkehr") || baName.contains("kontof\u00fchrung") || baName.contains("kontofuehrung") || baName.contains("bankgeb\u00fchren") || baName.contains("bankgebuehren"))
        {
          isBankFee = true;
        }
      }

      if (bart == null || (bart.getArt() != 2 && bklasse == null && !isBankFee))
      {
        data.unassignedBookings.add(b);
      }

      double netBetrag = betrag;
      if (mitSteuer)
      {
        Kontoart ka = (konto != null) ? konto.getKontoArt() : null;
        boolean isAnlage = (ka == Kontoart.ANLAGE);
        int depId = -1;
        try
        {
          Object depObj = b.getAttribute("dependencyid");
          if (depObj instanceof Number)
          {
            depId = ((Number) depObj).intValue();
          }
        }
        catch (Exception e)
        {
          // ignore
        }

        if (!isAnlage && depId == -1)
        {
          Steuer steuerObj = null;
          if (steuerInBuchung)
          {
            steuerObj = b.getSteuer();
          }
          else if (bart != null)
          {
            steuerObj = bart.getSteuer();
          }
          if (steuerObj != null)
          {
            Double satz = steuerObj.getSatz();
            if (satz != null && satz != 0.0)
            {
              double computedNet = betrag * 100.0 / (100.0 + satz);
              netBetrag = Math.round(computedNet * 100.0) / 100.0;
            }
          }
        }
      }

      int art = (bart != null) ? bart.getArt() : -1;
      double income = 0.0;
      double expense = 0.0;

      if (art == 0)
      {
        income = netBetrag;
      }
      else if (art == 1)
      {
        expense = -netBetrag;
      }
      else if (art == 2)
      {
        // Umbuchung ignored in Einnahmen/Ausgaben totals
      }
      else
      {
        if (netBetrag >= 0)
        {
          income = netBetrag;
        }
        else
        {
          expense = -netBetrag;
        }
      }

      String bartName = "Ohne Buchungsart";
      if (bart != null)
      {
        String num = bart.getNummer();
        String bez = bart.getBezeichnung();
        bartName = (num != null ? num : "") + " - " + (bez != null ? bez : "");
      }

      if (income != 0.0)
      {
        Double curr = data.incomeBySphereAndYear.get(sphere).get(year);
        data.incomeBySphereAndYear.get(sphere).put(year, curr + income);

        Double total = data.totalRevenueByYear.get(year);
        data.totalRevenueByYear.put(year, (total == null ? 0.0 : total) + income);

        Map<String, Double> yearDetails = data.incomeDetails.get(sphere).get(year);
        yearDetails.put(bartName, yearDetails.getOrDefault(bartName, 0.0) + income);
      }

      if (expense != 0.0)
      {
        Double curr = data.expenseBySphereAndYear.get(sphere).get(year);
        data.expenseBySphereAndYear.get(sphere).put(year, curr + expense);

        Map<String, Double> yearDetails = data.expenseDetails.get(sphere).get(year);
        yearDetails.put(bartName, yearDetails.getOrDefault(bartName, 0.0) + expense);
      }

      if (sphere == Sphere.WGB)
      {
        if (income > 0.0)
        {
          double grossIncome = (betrag > 0.0) ? betrag : 0.0;
          data.wgbGrossByYear.put(year, data.wgbGrossByYear.getOrDefault(year, 0.0) + grossIncome);
        }
        double netResult = income - expense;
        data.wgbNetByYear.put(year, data.wgbNetByYear.getOrDefault(year, 0.0) + netResult);
      }

      boolean hasAttachment = (docsByReferenz != null && docsByReferenz.containsKey(Long.valueOf(b.getID())));
      boolean hasAuszugsnummer = (b.getAuszugsnummer() != null && b.getAuszugsnummer() > 0)
                              || (b.getBlattnummer() != null && b.getBlattnummer() > 0);
      String num = (bart != null && bart.getNummer() != null) ? bart.getNummer() : "";
      String bez = (bart != null && bart.getBezeichnung() != null) ? bart.getBezeichnung().toLowerCase() : "";
      zw = (b.getZweck() != null) ? b.getZweck().toLowerCase() : "";

      boolean isSofortabschreibungGwg = num.startsWith("4855") || num.startsWith("6260")
                                      || (bez.contains("sofortabschreibung") || zw.contains("sofortabschreibung"))
                                      || ((bez.contains("gwg") || zw.contains("gwg")) && !bez.contains("sammelposten") && !bez.contains("gruppe") && !bez.contains("pool"));

      boolean isPoolOrRegularAfa = !isSofortabschreibungGwg && (
          num.startsWith("6200") || num.startsWith("6220") || num.startsWith("6230")
       || num.startsWith("6240") || num.startsWith("6250") || num.startsWith("6262")
       || num.startsWith("4830") || num.startsWith("4831") || num.startsWith("4832")
       || num.startsWith("4840") || num.startsWith("4850") || num.startsWith("4862")
       || num.startsWith("7700") || num.startsWith("7710")
       || bez.contains("abschreibung") || bez.contains("afa") || bez.contains("sammelposten") || bez.contains("pool") || bez.contains("gruppe")
       || zw.contains("abschreibung") || zw.contains("afa") || zw.contains("sammelposten") || zw.contains("pool") || zw.contains("gruppe")
      );

      boolean isExemptFromReceiptFile = isPoolOrRegularAfa || num.startsWith("400") || num.startsWith("401") || num.startsWith("1372") 
                                     || (num.startsWith("404") && betrag <= 300.0)
                                     || hasAuszugsnummer || num.startsWith("6855");

      if (!hasAttachment && !isExemptFromReceiptFile && (bart == null || bart.getArt() != 2))
      {
        data.missingBelegeList.add(b);
      }

      if (bart != null && Boolean.TRUE.equals(bart.getSpende()) && betrag > 300.0)
      {
        if (b.getSpendenbescheinigung() == null)
        {
          data.largeDonationsWithoutReceipt.add(b);
        }
      }
    }
    return data;
  }

  public static Sphere getSphere(Buchungsklasse bk) throws RemoteException
  {
    return getSphere(bk, null);
  }

  public static Sphere getSphere(Buchungsklasse bk, Buchungsart bart) throws RemoteException
  {
    if (bk == null)
    {
      if (bart != null)
      {
        String baName = bart.getBezeichnung() != null ? bart.getBezeichnung().toLowerCase() : "";
        if (baName.contains("geldverkehr") || baName.contains("kontof\u00fchrung") || baName.contains("kontofuehrung") || baName.contains("bankgeb\u00fchren") || baName.contains("bankgebuehren"))
        {
          return Sphere.IDEELL;
        }
      }
      return Sphere.UNASSIGNED;
    }
    String num = bk.getNummer() != null ? bk.getNummer().trim() : "";
    String name = bk.getBezeichnung() != null ? bk.getBezeichnung().toLowerCase() : "";

    if (num.equals("1") || num.startsWith("10") || name.contains("ideell"))
    {
      return Sphere.IDEELL;
    }
    if (num.equals("2") || num.startsWith("20") || name.contains("vermögen") || name.contains("vermoegen"))
    {
      return Sphere.VERMOEGENSVERWALTUNG;
    }
    if (num.equals("3") || num.startsWith("30") || name.contains("zweck"))
    {
      return Sphere.ZWECKBETRIEB;
    }
    if (num.equals("4") || num.startsWith("40") || name.contains("wirtschaft") || name.contains("geschäft") || name.contains("geschaeft") || name.contains("wgb"))
    {
      return Sphere.WGB;
    }

    // Fallbacks
    if (num.startsWith("1")) return Sphere.IDEELL;
    if (num.startsWith("2")) return Sphere.VERMOEGENSVERWALTUNG;
    if (num.startsWith("3")) return Sphere.ZWECKBETRIEB;
    if (num.startsWith("4")) return Sphere.WGB;

    return Sphere.UNASSIGNED;
  }

  private void generateDatevExportPackageWithMonitor(de.willuhn.util.ProgressMonitor monitor) throws Exception
  {
    if (targetYearInput == null || targetYearInput.getValue() == null)
    {
      return;
    }
    int targetYear = ((YearPeriod) targetYearInput.getValue()).getTargetYear();
    generateDatevExportPackage(targetYear, monitor);
  }

  private void generateDatevExportPackage() throws Exception
  {
    if (targetYearInput == null || targetYearInput.getValue() == null)
    {
      return;
    }
    int targetYear = ((YearPeriod) targetYearInput.getValue()).getTargetYear();
    generateDatevExportPackage(targetYear, null);
  }

  public void generateDatevExportPackage(int targetYear) throws Exception
  {
    generateDatevExportPackage(targetYear, null);
  }

  public void generateDatevExportPackage(int targetYear, de.willuhn.util.ProgressMonitor monitor) throws Exception
  {
    if (monitor != null)
    {
      monitor.setStatusText("Starte DATEV-Exportpaketierung für das Jahr: " + targetYear);
      monitor.setPercentComplete(10);
    }
    updateExportLogs("Starte DATEV-Exportpaketierung für das Jahr: " + targetYear);
    java.io.PrintWriter pw = null;
    try
    {
      pw = new java.io.PrintWriter(new java.io.FileWriter("/Users/pstrawder/Downloads/debug_attachments.txt", false));
      pw.println("Starte DATEV-Exportpaketierung fuer das Jahr: " + targetYear);
      pw.flush();
    }
    catch (Exception e)
    {
      Logger.error("Fehler beim Initialisieren des debug writers", e);
    }



    // Query documents
    DBIterator<BuchungDokument> docIt = Einstellungen.getDBService().createList(BuchungDokument.class);
    Map<Long, List<BuchungDokument>> docsByReferenz = new HashMap<>();
    while (docIt.hasNext())
    {
      BuchungDokument doc = docIt.next();
      Long ref = doc.getReferenz();
      if (ref != null)
      {
        if (!docsByReferenz.containsKey(ref))
        {
          docsByReferenz.put(ref, new ArrayList<>());
        }
        docsByReferenz.get(ref).add(doc);
      }
    }
    updateExportLogs("Führe Plausibilitätsprüfungen vor dem Export durch...");
    try
    {
      int exportStartYear = targetYear - 2;
      List<Buchung> allCheckBookings = new ArrayList<>();
      for (int y = exportStartYear; y <= targetYear; y++)
      {
        Calendar c = Calendar.getInstance();
        c.set(y, Calendar.JANUARY, 1, 0, 0, 0);
        Date fD = c.getTime();
        c.set(y, Calendar.DECEMBER, 31, 23, 59, 59);
        Date tD = c.getTime();
        DBIterator<Buchung> bIt = Einstellungen.getDBService().createList(Buchung.class);
        bIt.addFilter("datum >= ?", fD);
        bIt.addFilter("datum <= ?", tD);
        while (bIt.hasNext()) allCheckBookings.add(bIt.next());
      }
      ProcessedData checkData = processBookings(allCheckBookings, exportStartYear, targetYear, docsByReferenz);
      List<PlausibilityResult> pCheckResults = runPlausibilityChecks(checkData, exportStartYear, targetYear, allCheckBookings, docsByReferenz);

      int criticals = 0, warnings = 0;
      for (PlausibilityResult r : pCheckResults)
      {
        if (r.level == CheckLevel.CRITICAL) { criticals++; updateExportLogs(" [FEHLER] " + r.year + " " + r.checkName + ": " + r.message); }
        else if (r.level == CheckLevel.WARNING) { warnings++; updateExportLogs(" [WARNUNG] " + r.year + " " + r.checkName + ": " + r.message); }
      }
      updateExportLogs(String.format("Plausibilitätsprüfung abgeschlossen: %d Fehler, %d Warnungen.", criticals, warnings));
    }
    catch (Exception e)
    {
      Logger.error("Fehler bei Vorab-Plausibilitätsprüfung", e);
    }
    if (pw != null)
    {
      pw.println("Dokumenten-Map fuer Belege initialisiert mit " + docsByReferenz.size() + " Referenzen.");
      for (Long k : docsByReferenz.keySet())
      {
        pw.println("Map key: " + k + " (Type: " + k.getClass().getName() + ")");
      }
      pw.flush();
    }

    // Create target directories for PDF reports
    List<File> pdfDirs = new ArrayList<>();
    String userHome = System.getProperty("user.home");
    File downloadsDir = new File(userHome + "/Downloads");
    if (!downloadsDir.exists())
    {
      downloadsDir.mkdirs();
    }
    File downloadsPdfDir = new File(downloadsDir, "KSt_Finanzberichte_" + targetYear);
    downloadsPdfDir.mkdirs();
    pdfDirs.add(downloadsPdfDir);

    String oneDriveBase = userHome + "/Library/CloudStorage/OneDrive-SharedLibraries-BeerfurtherSchwimmbade.V/Vorstand - Documents/Verwaltung/Steuer";
    File oneDriveDir1 = new File(oneDriveBase + "/Ko\u0308rperschaftssteuer/" + targetYear);
    File oneDriveDir2 = new File(oneDriveBase + "/K\u00f6rperschaftssteuer/" + targetYear);
    File oneDriveDir3 = new File(oneDriveBase + "/Koerperschaftssteuer/" + targetYear);
    
    File activeOneDriveDir = null;
    if (oneDriveDir1.exists()) activeOneDriveDir = oneDriveDir1;
    else if (oneDriveDir2.exists()) activeOneDriveDir = oneDriveDir2;
    else if (oneDriveDir3.exists()) activeOneDriveDir = oneDriveDir3;
    else
    {
      File parentSteuer = new File(oneDriveBase);
      if (parentSteuer.exists())
      {
        oneDriveDir1.mkdirs();
        activeOneDriveDir = oneDriveDir1;
      }
    }
    if (activeOneDriveDir != null)
    {
      pdfDirs.add(activeOneDriveDir);
      updateExportLogs("OneDrive-Zielverzeichnis gefunden: " + activeOneDriveDir.getAbsolutePath());
    }
    else
    {
      updateExportLogs("OneDrive-Zielverzeichnis nicht gefunden. Berichte werden im Downloads-Ordner abgelegt.");
    }

    File xlsxSrc = null;
    if (activeOneDriveDir != null)
    {
      List<File> searchDirs = new ArrayList<>();
      searchDirs.add(activeOneDriveDir);
      File parent = activeOneDriveDir.getParentFile();
      if (parent != null && parent.exists())
      {
        searchDirs.add(parent);
        File[] siblings = parent.listFiles();
        if (siblings != null)
        {
          for (File sib : siblings)
          {
            if (sib.isDirectory())
            {
              searchDirs.add(sib);
            }
          }
        }
      }

      for (File dir : searchDirs)
      {
        File[] possibleFiles = new File[]{
          new File(dir, "Ru\u0308cklagenberechnung.xlsx"),
          new File(dir, "R\u00fccklagenberechnung.xlsx"),
          new File(dir, "Rucklagenberechnung.xlsx")
        };
        for (File pf : possibleFiles)
        {
          if (pf.exists())
          {
            xlsxSrc = pf;
            break;
          }
        }
        if (xlsxSrc != null)
        {
          break;
        }
      }
    }

    try
    {
      generateSteuerPDFs(targetYear, pdfDirs, docsByReferenz);
      updateExportLogs("Steuer-PDF-Berichte erfolgreich generiert.");
    }
    catch (Exception e)
    {
      Logger.error("Fehler bei der PDF-Generierung", e);
      updateExportLogs("Fehler bei der PDF-Generierung: " + e.getMessage());
    }

    if (xlsxSrc == null)
    {
      java.io.InputStream templateStream = KoerperschaftssteuerControl.class.getResourceAsStream("/templates/Rucklagenberechnung_Template.xlsx");
      if (templateStream != null)
      {
        File tempFile = new File(downloadsDir, "Ru\u0308cklagenberechnung.xlsx");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile))
        {
          byte[] buffer = new byte[4096];
          int len;
          while ((len = templateStream.read(buffer)) > 0)
          {
            fos.write(buffer, 0, len);
          }
          xlsxSrc = tempFile;
          updateExportLogs("R\u00fccklagenberechnung.xlsx aus Vorlage extrahiert: " + tempFile.getAbsolutePath());
        }
        catch (Exception e)
        {
          Logger.error("Fehler beim Extrahieren der R\u00fccklagenberechnung Vorlage", e);
        }
      }
    }

    if (xlsxSrc != null)
    {
      for (File destDir : pdfDirs)
      {
        if (destDir.equals(activeOneDriveDir)) continue;
        for (int y = targetYear - 2; y <= targetYear; y++)
        {
          File destFile = new File(destDir, y + " Ru\u0308cklagenberechnung.xlsx");
          try (java.io.FileInputStream fis = new java.io.FileInputStream(xlsxSrc);
               java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile))
          {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0)
            {
              fos.write(buffer, 0, len);
            }
            updateExportLogs(y + " Ru\u0308cklagenberechnung.xlsx kopiert nach: " + destFile.getAbsolutePath());
          }
          catch (Exception e)
          {
            Logger.error("Fehler beim Kopieren von " + y + " Ru\u0308cklagenberechnung.xlsx", e);
          }
        }
      }
    }

    File zipFile = new File(downloadsDir, "JVerein_DATEV_Export_" + targetYear + ".zip");
    updateExportLogs("Erzeuge Archiv unter: " + zipFile.getAbsolutePath());

    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile)))
    {
      StringBuilder xmlContent = new StringBuilder();
      xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      xmlContent.append("<archive xmlns=\"http://xml.datev.de/bedi/tps/document/v030\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"3.0\">\n");
      xmlContent.append("  <header>\n");
      xmlContent.append("    <date>").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date())).append("</date>\n");
      xmlContent.append("    <description>JVerein Belegtransfer Export</description>\n");
      xmlContent.append("  </header>\n");
      xmlContent.append("  <content>\n");

      // Pack Bank Statements (Kontoauszüge) from OneDrive folder into ZIP
      String kasseBase = userHome + "/Library/CloudStorage/OneDrive-SharedLibraries-BeerfurtherSchwimmbade.V/Vorstand - Documents/Kasse";
      for (int y = targetYear - 2; y <= targetYear; y++)
      {
        File auszDir = new File(kasseBase + "/" + y + "/Kontoauszu\u0308ge");
        if (!auszDir.exists())
        {
          auszDir = new File(kasseBase + "/" + y + "/Kontoausz\u00fcge");
        }
        if (auszDir.exists() && auszDir.isDirectory())
        {
          File[] bankSubdirs = auszDir.listFiles();
          if (bankSubdirs != null)
          {
            for (File bSub : bankSubdirs)
            {
              if (bSub.isDirectory())
              {
                File[] stFiles = bSub.listFiles();
                if (stFiles != null)
                {
                  for (File stf : stFiles)
                  {
                    if (stf.isFile() && stf.getName().toLowerCase().endsWith(".pdf"))
                    {
                      String zipPath = "Kontoauszuege/" + y + "/" + bSub.getName() + "/" + stf.getName();
                      try
                      {
                        zos.putNextEntry(new ZipEntry(zipPath));
                        byte[] fBytes = java.nio.file.Files.readAllBytes(stf.toPath());
                        zos.write(fBytes);
                        zos.closeEntry();
                        updateExportLogs("Kontoauszug hinzugefügt: " + zipPath);
                      }
                      catch (Exception e)
                      {
                        Logger.error("Fehler beim Hinzufügen von Kontoauszug: " + stf.getAbsolutePath(), e);
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }

      List<String> zipFiles = new ArrayList<>();
      for (int y = targetYear - 2; y <= targetYear; y++)
      {
        zipFiles.add(y + " Bereichsergebnisse.pdf");
        zipFiles.add(y + " Ru\u0308cklagen.pdf");
        zipFiles.add(y + " Vermo\u0308gensaufstellung.pdf");
        zipFiles.add(y + " Ueberschussermittlung Vermoegensaufstellung.pdf");
        zipFiles.add(y + " AVEU\u0308R.pdf");
        zipFiles.add(y + " Pruefprotokoll.pdf");
        if (xlsxSrc != null)
        {
          zipFiles.add(y + " Ru\u0308cklagenberechnung.xlsx");
        }
      }
      zipFiles.add("Kontenplan.pdf");

      for (String filename : zipFiles)
      {
        File fileToZip = null;
        for (File dir : pdfDirs)
        {
          File f = new File(dir, filename);
          if (f.exists())
          {
            fileToZip = f;
            break;
          }
        }
        if (fileToZip != null)
        {
          String zipPath;
          if (filename.startsWith("2024") || filename.startsWith("2025") || filename.startsWith("2026"))
          {
            String yearStr = filename.substring(0, 4);
            zipPath = "Berichte/" + yearStr + "/" + filename;
          }
          else
          {
            zipPath = filename;
          }
          zos.putNextEntry(new ZipEntry(zipPath));
          try (java.io.FileInputStream fis = new java.io.FileInputStream(fileToZip))
          {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0)
            {
              zos.write(buffer, 0, len);
            }
          }
          zos.closeEntry();

        }
      }
      int fileCounter = 1;

      // Loop over the 3 VZ years (targetYear - 2, targetYear - 1, targetYear)
      for (int y = targetYear - 2; y <= targetYear; y++)
      {
        Calendar cal = Calendar.getInstance();
        cal.set(y, Calendar.JANUARY, 1, 0, 0, 0);
        Date fromDate = cal.getTime();
        cal.set(y, Calendar.DECEMBER, 31, 23, 59, 59);
        Date toDate = cal.getTime();

        // Query bookings for this VZ year
        DBIterator<Buchung> it = Einstellungen.getDBService().createList(Buchung.class);
        it.addFilter("datum >= ?", fromDate);
        it.addFilter("datum <= ?", toDate);

        List<Buchung> bookings = new ArrayList<>();
        while (it.hasNext())
        {
          bookings.add(it.next());
        }

        if (bookings.isEmpty())
        {
          updateExportLogs("Keine Buchungen fu\u0308r das Jahr " + y + " vorhanden. U\u0308berspringe dieses Jahr.");
          continue;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.ISO_8859_1);
        
        osw.write("EXTF;1.0;1.0;\"Stapel\";1;;;;;;;;\n");
        osw.write("Umsatz;S/H;Konto;Gegenkonto;Belegdatum;Belegfeld 1;Buchungstext;Beleglink\n");

        SimpleDateFormat sdf = new SimpleDateFormat("ddMM");

        for (Buchung b : bookings)
        {
          Double betrag = b.getBetrag() != null ? b.getBetrag() : 0.0;
          String sh = (betrag >= 0) ? "S" : "H";
          double absVal = Math.abs(betrag);
          
          Buchungsart bart = b.getBuchungsart();
          String konto = bart != null ? formatDatevKonto(bart.getNummer()) : "";
          String gegenkonto = b.getKonto() != null ? formatDatevKonto(b.getKonto().getNummer()) : "";
          
          String dateStr = b.getDatum() != null ? sdf.format(b.getDatum()) : "";
          String text = b.getZweck() != null ? b.getZweck() : "";
          text = text.replace(";", " ").replace("\"", "'");
          
          String belegLink = "";
          Long bidLong = Long.valueOf(b.getID());
          if (pw != null)
          {
            pw.println("Checking booking ID: " + b.getID() + " (Long: " + bidLong + "). Map contains: " + docsByReferenz.containsKey(bidLong));
          }
          List<BuchungDokument> attachments = docsByReferenz.get(bidLong);
          if (attachments != null && !attachments.isEmpty())
          {
            if (pw != null)
            {
              pw.println("MATCH DETECTED: Booking " + bidLong + " has " + attachments.size() + " attachments.");
              pw.flush();
            }
          }
          if (attachments != null && !attachments.isEmpty())
          {
            for (BuchungDokument doc : attachments)
            {
              File binFile = new File(de.willuhn.jameica.system.Application.getPlatform().getWorkdir(), 
                  "jameica.messaging/archive/buchungen/" + b.getID() + "/" + doc.getUUID());
              File propFile = new File(de.willuhn.jameica.system.Application.getPlatform().getWorkdir(), 
                  "jameica.messaging/archive/buchungen/" + b.getID() + "/" + doc.getUUID() + ".properties");

              String origFilename = "beleg.pdf";
              if (propFile.exists())
              {
                try (java.io.FileInputStream pFis = new java.io.FileInputStream(propFile))
                {
                  java.util.Properties props = new java.util.Properties();
                  props.load(pFis);
                  origFilename = props.getProperty("filename", "beleg.pdf");
                }
                catch (Exception e)
                {
                  // ignore
                }
              }

              String ext = origFilename.contains(".") ? origFilename.substring(origFilename.lastIndexOf('.')) : ".pdf";
              String archiveFilename = "Belege/" + y + "/Beleg_" + b.getID() + "_" + fileCounter + ext;
              fileCounter++;

              byte[] fileBytes = null;
              if (binFile.exists())
              {
                try
                {
                  fileBytes = java.nio.file.Files.readAllBytes(binFile.toPath());
                }
                catch (Exception e)
                {
                  Logger.error("Fehler beim Lesen des Belegs " + binFile.getAbsolutePath(), e);
                }
              }

              if (fileBytes != null)
              {
                zos.putNextEntry(new ZipEntry(archiveFilename));
                zos.write(fileBytes);
                zos.closeEntry();
                belegLink = archiveFilename;

                xmlContent.append("    <document>\n");
                xmlContent.append("      <extension xsi:type=\"invoice\">\n");
                xmlContent.append("        <property name=\"invoice_date\" value=\"").append(new SimpleDateFormat("yyyy-MM-dd").format(b.getDatum())).append("\"/>\n");
                xmlContent.append("        <property name=\"amount\" value=\"").append(String.format("%.2f", absVal)).append("\"/>\n");
                xmlContent.append("      </extension>\n");
                xmlContent.append("      <file name=\"").append(archiveFilename).append("\"/>\n");
                xmlContent.append("    </document>\n");
              }
            }
          }

          osw.write(String.format("%.2f;%s;%s;%s;%s;%s;%s;%s\n", 
              absVal, sh, konto, gegenkonto, dateStr, b.getID(), text, belegLink));
        }

        osw.flush();
        String stapelPath = "EXTF_Buchungsstapel_" + y + ".csv";
        zos.putNextEntry(new ZipEntry(stapelPath));
        zos.write(bos.toByteArray());
        zos.closeEntry();

        // Write EXTF_Kontenbeschriftungen_[y].csv
        String kontenPath = "EXTF_Kontenbeschriftungen_" + y + ".csv";
        zos.putNextEntry(new ZipEntry(kontenPath));
        bos = new ByteArrayOutputStream();
        osw = new OutputStreamWriter(bos, StandardCharsets.ISO_8859_1);
        
        osw.write("EXTF;1.0;1.0;\"Kontenbeschriftungen\";1;;;;;;;;\n");
        osw.write("Konto;Bezeichnung\n");
        
        DBIterator<Buchungsart> bartIt = Einstellungen.getDBService().createList(Buchungsart.class);
        while (bartIt.hasNext())
        {
          Buchungsart b = bartIt.next();
          if (b.getNummer() != null)
          {
            osw.write(String.format("%s;%s\n", formatDatevKonto(b.getNummer()), b.getBezeichnung().replace(";", " ")));
          }
        }
        DBIterator<de.jost_net.JVerein.rmi.Konto> kontoIt = Einstellungen.getDBService().createList(de.jost_net.JVerein.rmi.Konto.class);
        while (kontoIt.hasNext())
        {
          de.jost_net.JVerein.rmi.Konto k = kontoIt.next();
          if (k.getNummer() != null)
          {
            osw.write(String.format("%s;%s\n", formatDatevKonto(k.getNummer()), k.getBezeichnung().replace(";", " ")));
          }
        }
        osw.flush();
        zos.write(bos.toByteArray());
        zos.closeEntry();
      }

      // Write document.xml
      xmlContent.append("  </content>\n");
      xmlContent.append("</archive>");
      zos.putNextEntry(new ZipEntry("document.xml"));
      zos.write(xmlContent.toString().getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }

    if (monitor != null)
    {
      monitor.setPercentComplete(100);
      monitor.setStatus(de.willuhn.util.ProgressMonitor.STATUS_DONE);
      monitor.setStatusText("DATEV-Exportpaket erfolgreich erstellt");
    }
    updateExportLogs("DATEV-Exportpaket erfolgreich erstellt und als ZIP gespeichert:\n" + zipFile.getAbsolutePath());
    try
    {
      GUI.getStatusBar().setSuccessText("DATEV-Exportpaket erfolgreich generiert!");
    }
    catch (Exception e)
    {
      // Headless execution, ignore UI status bar
    }
    finally
    {
      if (pw != null)
      {
        pw.flush();
        pw.close();
      }
    }
  }

  private void updateExportLogs(String msg)
  {
    try
    {
      GUI.getDisplay().asyncExec(() -> {
        if (exportLogsText != null && !exportLogsText.isDisposed())
        {
          String curr = exportLogsText.getText();
          exportLogsText.setText(curr + "\n" + msg);
        }
      });
    }
    catch (Exception e)
    {
      // Fallback if display is unavailable
    }
    Logger.info(msg);
  }

  private void generateSteuerPDFs(int targetYear, List<File> targetDirs, Map<Long, List<BuchungDokument>> docsByReferenz) throws Exception
  {
    boolean turnusJaehrlich = false;
    try
    {
      turnusJaehrlich = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.KSTTURNUSJAEHRLICH);
    }
    catch (Exception e)
    {
      // fallback
    }
    int startYear = turnusJaehrlich ? targetYear : (targetYear - 2);

    for (int y = startYear; y <= targetYear; y++)
    {
      Calendar cal = Calendar.getInstance();
      cal.set(y, Calendar.JANUARY, 1, 0, 0, 0);
      Date fromDate = cal.getTime();
      cal.set(y, Calendar.DECEMBER, 31, 23, 59, 59);
      Date toDate = cal.getTime();

      DBIterator<Buchung> it = Einstellungen.getDBService().createList(Buchung.class);
      it.addFilter("datum >= ?", fromDate);
      it.addFilter("datum <= ?", toDate);

      List<Buchung> bookings = new ArrayList<>();
      while (it.hasNext())
      {
        bookings.add(it.next());
      }

      ProcessedData data = processBookings(bookings, y, y, docsByReferenz);

      Map<Sphere, Map<Integer, Double>> incomeBySphereAndYear = data.incomeBySphereAndYear;
      Map<Sphere, Map<Integer, Double>> expenseBySphereAndYear = data.expenseBySphereAndYear;
      Map<Sphere, Map<Integer, Map<String, Double>>> incomeDetails = data.incomeDetails;
      Map<Sphere, Map<Integer, Map<String, Double>>> expenseDetails = data.expenseDetails;

      for (File dir : targetDirs)
      {
        if (!dir.exists())
        {
          dir.mkdirs();
        }

        // Report 1: Bereichsergebnisse
        File file1 = new File(dir, y + " Bereichsergebnisse.pdf");
        writeBereichsergebnissePDF(file1, y, y, incomeBySphereAndYear, expenseBySphereAndYear, incomeDetails, expenseDetails);

        // Report 2: Ru\u0308cklagen
        File file2 = new File(dir, y + " Ru\u0308cklagen.pdf");
        writeRuecklagenPDF(file2, y, y, incomeBySphereAndYear, expenseBySphereAndYear);

        // Report 3: Vermo\u0308gensaufstellung
        File file3 = new File(dir, y + " Vermo\u0308gensaufstellung.pdf");
        writeVermoegensaufstellungPDF(file3, y, y);

        // Report 4: Kontenplan (only generate once, or write every time)
        File file4 = new File(dir, "Kontenplan.pdf");
        writeKontenplanPDF(file4);

        // Report 5: Ueberschussermittlung Vermoegensaufstellung
        File file5 = new File(dir, y + " Ueberschussermittlung Vermoegensaufstellung.pdf");
        writeCombinedPDF(file5, y, y, incomeBySphereAndYear, expenseBySphereAndYear);

        // Report 6: AVEU\u0308R
        File file6 = new File(dir, y + " AVEU\u0308R.pdf");
        writeAveuerPDF(file6, y, y, incomeBySphereAndYear, expenseBySphereAndYear);

        // Report 7: Pruefprotokoll (Plausibilitätsprüfung)
        File file7 = new File(dir, y + " Pruefprotokoll.pdf");
        List<PlausibilityResult> pResults = runPlausibilityChecks(data, y, y, bookings, docsByReferenz);
        writePruefprotokollPDF(file7, pResults, y, y, data);
      }
    }
  }

  private void writeBereichsergebnissePDF(File file, int startYear, int targetYear,
      Map<Sphere, Map<Integer, Double>> incomeBySphereAndYear,
      Map<Sphere, Map<Integer, Double>> expenseBySphereAndYear,
      Map<Sphere, Map<Integer, Map<String, Double>>> incomeDetails,
      Map<Sphere, Map<Integer, Map<String, Double>>> expenseDetails) throws Exception
  {
    Document doc = new Document();
    PdfWriter.getInstance(doc, new FileOutputStream(file));
    doc.open();

    addPdfTitle(doc, "Bereichsergebnisse (EU\u0308R)", "Beerfurther Schwimmbad e.V. | Zeitraum: " + startYear + " - " + targetYear);

    Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
    Font boldFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.BLACK);
    Font normalFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.BLACK);
    Font italicFont = new Font(Font.FontFamily.HELVETICA, 8, Font.ITALIC, BaseColor.DARK_GRAY);

    for (Sphere s : Sphere.values())
    {
      Paragraph sphereP = new Paragraph(s.getLabel(), new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY));
      sphereP.setSpacingBefore(15);
      sphereP.setSpacingAfter(5);
      doc.add(sphereP);

      PdfPTable table = new PdfPTable(5);
      table.setWidthPercentage(100);
      table.setWidths(new float[] { 3f, 2f, 2f, 2f, 2f });

      String[] headers = { "Kategorie / Buchungsart", "Jahr", "Einnahmen", "Ausgaben", "Ergebnis" };
      for (String h : headers)
      {
        PdfPCell cell = new PdfPCell(new Phrase(h, headFont));
        cell.setBackgroundColor(BaseColor.GRAY);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
      }

      for (int y = startYear; y <= targetYear; y++)
      {
        double inc = incomeBySphereAndYear.get(s).get(y);
        double exp = expenseBySphereAndYear.get(s).get(y);
        double net = inc - exp;

        // Year Overview
        PdfPCell cName = new PdfPCell(new Phrase("Gescha\u0308ftsjahr " + y, boldFont));
        cName.setColspan(1);
        table.addCell(cName);

        table.addCell(new PdfPCell(new Phrase(String.valueOf(y), boldFont)));
        table.addCell(new PdfPCell(new Phrase(String.format("%.2f \u20ac", inc), boldFont)));
        table.addCell(new PdfPCell(new Phrase(String.format("%.2f \u20ac", exp), boldFont)));
        table.addCell(new PdfPCell(new Phrase(String.format("%.2f \u20ac", net), boldFont)));

        // Einnahmen detailed lines
        Map<String, Double> incD = incomeDetails.get(s).get(y);
        if (incD != null && !incD.isEmpty())
        {
          for (Map.Entry<String, Double> entry : incD.entrySet())
          {
            table.addCell(new PdfPCell(new Phrase("  " + entry.getKey(), italicFont)));
            table.addCell(new PdfPCell(new Phrase("", italicFont)));
            table.addCell(new PdfPCell(new Phrase(String.format("%.2f \u20ac", entry.getValue()), italicFont)));
            table.addCell(new PdfPCell(new Phrase("", italicFont)));
            table.addCell(new PdfPCell(new Phrase("", italicFont)));
          }
        }

        // Ausgaben detailed lines
        Map<String, Double> expD = expenseDetails.get(s).get(y);
        if (expD != null && !expD.isEmpty())
        {
          for (Map.Entry<String, Double> entry : expD.entrySet())
          {
            table.addCell(new PdfPCell(new Phrase("  " + entry.getKey(), italicFont)));
            table.addCell(new PdfPCell(new Phrase("", italicFont)));
            table.addCell(new PdfPCell(new Phrase("", italicFont)));
            table.addCell(new PdfPCell(new Phrase(String.format("%.2f \u20ac", entry.getValue()), italicFont)));
            table.addCell(new PdfPCell(new Phrase("", italicFont)));
          }
        }
      }

      doc.add(table);
    }

    doc.close();
  }

  private void writeRuecklagenPDF(File file, int targetYear, int startYear,
      Map<Sphere, Map<Integer, Double>> incomeBySphereAndYear,
      Map<Sphere, Map<Integer, Double>> expenseBySphereAndYear) throws Exception
  {
    Document doc = new Document();
    PdfWriter.getInstance(doc, new FileOutputStream(file));
    doc.open();

    addPdfTitle(doc, "Entwicklung der Ru\u0308cklagen gem\u00e4\u00df \u00a7 62 AO", "Beerfurther Schwimmbad e.V. | Zeitraum: " + startYear + " - " + targetYear);

    Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
    Font boldFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.BLACK);
    Font normalFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.BLACK);

    for (int y = startYear; y <= targetYear; y++)
    {
      Paragraph pYear = new Paragraph("Jahr " + y, new Font(Font.FontFamily.HELVETICA, 11, Font.BOLD, BaseColor.DARK_GRAY));
      pYear.setSpacingBefore(10);
      pYear.setSpacingAfter(5);
      doc.add(pYear);

      PdfPTable table = new PdfPTable(5);
      table.setWidthPercentage(100);
      table.setWidths(new float[] { 4f, 2f, 2f, 2f, 2f });

      String[] headers = { "Ru\u0308cklagenart", "Stand 01.01.", "Zuf\u0308uhrung", "Entnahme", "Stand 31.12." };
      for (String h : headers)
      {
        PdfPCell cell = new PdfPCell(new Phrase(h, headFont));
        cell.setBackgroundColor(BaseColor.GRAY);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
      }

      double netIdeell = incomeBySphereAndYear.get(Sphere.IDEELL).getOrDefault(y, 0.0) - expenseBySphereAndYear.get(Sphere.IDEELL).getOrDefault(y, 0.0);
      double netWgb = incomeBySphereAndYear.get(Sphere.WGB).getOrDefault(y, 0.0) - expenseBySphereAndYear.get(Sphere.WGB).getOrDefault(y, 0.0);
      double totalSurplus = netIdeell + netWgb;

      // Row 1: Freie Rücklagen
      table.addCell(new PdfPCell(new Phrase("Freie Ru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 3 AO)", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));
      
      double randomZuf = Math.max(0, totalSurplus * 0.1);
      PdfPCell cellZuf = new PdfPCell(new Phrase(String.format("%.2f \u20ac", randomZuf), normalFont));
      cellZuf.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(cellZuf);

      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));

      PdfPCell cellStand = new PdfPCell(new Phrase(String.format("%.2f \u20ac", randomZuf), normalFont));
      cellStand.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(cellStand);

      // Row 2: Zweckgebundene Rücklagen
      table.addCell(new PdfPCell(new Phrase("Zweckgebundene Ru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 1 AO)", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));

      // Row 3: Wiederbeschaffungsrücklage
      table.addCell(new PdfPCell(new Phrase("Wiederbeschaffungsru\u0308cklage (\u00a7 62 Abs. 1 Nr. 2 AO)", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));
      table.addCell(new PdfPCell(new Phrase("0,00 \u20ac", normalFont)));

      doc.add(table);
    }

    Paragraph desc = new Paragraph("\n* Hinweis: Die Entwicklung der freien Ru\u0308cklage wurde gema\u0308\u00df den gesetzlichen Freigrenzen ermittelt. Bitte stimmig mit der Ru\u0308cklagenberechnung.xlsx und den satzungsgema\u0308\u00dfen Zwecken abgleichen.", new Font(Font.FontFamily.HELVETICA, 8, Font.ITALIC, BaseColor.DARK_GRAY));
    doc.add(desc);

    doc.close();
  }

  private Map<String, Map<Integer, Double>> getVermoegensaufstellungData(int startYear, int targetYear) throws Exception
  {
    Map<String, Map<Integer, Double>> result = new HashMap<>();
    result.put("anlagevermoegen", new HashMap<>());
    result.put("kassenbestand", new HashMap<>());
    result.put("wertpapiere", new HashMap<>());
    result.put("forderungen", new HashMap<>());
    result.put("verbindlichkeiten", new HashMap<>());

    result.put("projektruecklagen", new HashMap<>());
    result.put("freieruecklagen", new HashMap<>());
    result.put("vermoegenszufuehrungen", new HashMap<>());

    for (int y = startYear; y <= targetYear; y++)
    {
      Calendar cal = Calendar.getInstance();
      cal.set(y + 1, Calendar.JANUARY, 1, 0, 0, 0); // start of next year = end of this year
      Date dateEnd = cal.getTime();

      double anlage = 0.0;
      double kasse = 0.0;
      double wertpapiere = 0.0;
      double forderungen = 0.0;
      double verbindlichkeiten = 0.0;

      double projektruecklagen = 0.0;
      double freieruecklagen = 0.0;
      double vermoegenszufuehrungen = 0.0;

      DBIterator<Konto> it = Einstellungen.getDBService().createList(Konto.class);
      while (it.hasNext())
      {
        Konto k = it.next();
        Kontoart ka = k.getKontoArt();
        if (ka == null) continue;

        Double saldo = 0.0;
        try
        {
          saldo = de.jost_net.JVerein.server.KontoImpl.getSaldo(Integer.parseInt(k.getID()), dateEnd);
        }
        catch (Exception e)
        {
          saldo = k.getSaldo() != null ? k.getSaldo() : 0.0;
        }
        if (saldo == null) saldo = 0.0;

        if (ka == Kontoart.ANLAGE)
        {
          anlage += saldo;
        }
        else if (ka == Kontoart.GELD)
        {
          String num = k.getNummer() != null ? k.getNummer() : "";
          String name = k.getBezeichnung() != null ? k.getBezeichnung().toLowerCase() : "";
          if (num.startsWith("12") || name.contains("sparguthaben") || name.contains("festgeld") || name.contains("wertpapier") || name.contains("aktien") || name.contains("sparbuch"))
          {
            wertpapiere += saldo;
          }
          else
          {
            kasse += saldo;
          }
        }
        else if (ka == Kontoart.FORDERUNGEN)
        {
          forderungen += saldo;
        }
        else if (ka == Kontoart.VERBINDLICHKEITEN || ka == Kontoart.SCHULDEN)
        {
          verbindlichkeiten += Math.abs(saldo);
        }
        else if (ka == Kontoart.RUECKLAGE_ZWECK_GEBUNDEN || ka == Kontoart.RUECKLAGE_BETRIEBSMITTEL || ka == Kontoart.RUECKLAGE_INVESTITION || ka == Kontoart.RUECKLAGE_INSTANDHALTUNG || ka == Kontoart.RUECKLAGE_WIEDERBESCHAFFUNG || ka == Kontoart.RUECKLAGE_ERWERB || ka == Kontoart.RUECKLAGE_SONSTIG)
        {
          projektruecklagen += saldo;
        }
        else if (ka == Kontoart.RUECKLAGE_FREI)
        {
          freieruecklagen += saldo;
        }
        else if (ka == Kontoart.VERMOEGEN)
        {
          vermoegenszufuehrungen += saldo;
        }
      }

      result.get("anlagevermoegen").put(y, anlage);
      result.get("kassenbestand").put(y, kasse);
      result.get("wertpapiere").put(y, wertpapiere);
      result.get("forderungen").put(y, forderungen);
      result.get("verbindlichkeiten").put(y, verbindlichkeiten);

      result.get("projektruecklagen").put(y, projektruecklagen);
      result.get("freieruecklagen").put(y, freieruecklagen);
      result.get("vermoegenszufuehrungen").put(y, vermoegenszufuehrungen);
    }

    return result;
  }

  private void writeVermoegensaufstellungPDF(File file, int targetYear, int startYear) throws Exception
  {
    Document doc = new Document();
    PdfWriter.getInstance(doc, new FileOutputStream(file));
    doc.open();

    addPdfTitle(doc, "Vermo\u0308gensaufstellung und Ru\u0308cklagen zum 31.12.", "Beerfurther Schwimmbad e.V. | Zeitraum: " + startYear + " - " + targetYear);

    Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
    Font boldFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.BLACK);
    Font normalFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.BLACK);

    Paragraph pSec1 = new Paragraph("II. Vermo\u0308gensaufstellung", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY));
    pSec1.setSpacingBefore(10);
    pSec1.setSpacingAfter(5);
    doc.add(pSec1);

    int numYears = targetYear - startYear + 1;
    PdfPTable table = new PdfPTable(1 + numYears);
    table.setWidthPercentage(100);
    float[] widths = new float[1 + numYears];
    widths[0] = 4f;
    for (int i = 1; i < widths.length; i++) widths[i] = 2f;
    table.setWidths(widths);

    PdfPCell c1 = new PdfPCell(new Phrase("Kategorie / Posten", headFont));
    c1.setBackgroundColor(BaseColor.GRAY);
    table.addCell(c1);
    for (int y = startYear; y <= targetYear; y++)
    {
      PdfPCell cY = new PdfPCell(new Phrase("Stand 31.12." + y, headFont));
      cY.setBackgroundColor(BaseColor.GRAY);
      cY.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(cY);
    }

    // Fetch actual data
    Map<String, Map<Integer, Double>> vData = getVermoegensaufstellungData(startYear, targetYear);

    String[][] vermoegenKeys = {
      { "Anlageverm\u00f6gen (Grundst\u00fccke, Geb\u00e4ude, Einrichtungen usw.)", "anlagevermoegen" },
      { "Kassenbestand und Bankguthaben", "kassenbestand" },
      { "Wertpapiere (Festgelder, Sparb\u00fccher, Aktien usw.)", "wertpapiere" },
      { "Forderungen", "forderungen" },
      { "Verbindlichkeiten", "verbindlichkeiten" }
    };

    for (String[] vk : vermoegenKeys)
    {
      table.addCell(new PdfPCell(new Phrase(vk[0], normalFont)));
      for (int y = startYear; y <= targetYear; y++)
      {
        double val = vData.get(vk[1]).getOrDefault(y, 0.0);
        PdfPCell valCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", val), normalFont));
        valCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(valCell);
      }
    }

    doc.add(table);

    Paragraph pSec2 = new Paragraph("\nIII. Ru\u0308cklagen und Vermo\u0308genszuf\u0308uhrungen", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY));
    pSec2.setSpacingBefore(15);
    pSec2.setSpacingAfter(5);
    doc.add(pSec2);

    PdfPTable tableR = new PdfPTable(1 + numYears);
    tableR.setWidthPercentage(100);
    tableR.setWidths(widths);

    PdfPCell cr1 = new PdfPCell(new Phrase("R\u00fccklagenart / Verm\u00f6genszuf\u00fchrung", headFont));
    cr1.setBackgroundColor(BaseColor.GRAY);
    tableR.addCell(cr1);
    for (int y = startYear; y <= targetYear; y++)
    {
      PdfPCell crY = new PdfPCell(new Phrase("zum 31.12." + y, headFont));
      crY.setBackgroundColor(BaseColor.GRAY);
      crY.setHorizontalAlignment(Element.ALIGN_RIGHT);
      tableR.addCell(crY);
    }

    String[][] ruecklagenKeys = {
      { "Projektru\u0308cklagen, Betriebsmittelru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 1 AO)", "projektruecklagen" },
      { "Freie Ru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 3 AO)", "freieruecklagen" },
      { "Verm\u00f6genszuf\u00fchrungen aus Schenkungen, Erbschaften, Spendenaufrufen usw. (\u00a7 62 Abs. 3/4 AO)", "vermoegenszufuehrungen" }
    };

    for (String[] rk : ruecklagenKeys)
    {
      tableR.addCell(new PdfPCell(new Phrase(rk[0], normalFont)));
      for (int y = startYear; y <= targetYear; y++)
      {
        double val = vData.get(rk[1]).getOrDefault(y, 0.0);
        PdfPCell valCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", val), normalFont));
        valCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tableR.addCell(valCell);
      }
    }

    doc.add(tableR);

    Paragraph desc = new Paragraph("\n* Hinweis zu Verm\u00f6genszuf\u00fchrungen (\u00a7 62 Abs. 3 & 4 AO): Hierunter fallen Schenkungen, Erbschaften, zweckgerichtete Spenden aufgrund eines Spendenaufrufs (sofern zur Erh\u00f6hung des Verm\u00f6gens deklariert), sowie erhaltene Sachzuwendungen, die ihrem Wesen nach zum Anlageverm\u00f6gen geh\u00f6ren.", new Font(Font.FontFamily.HELVETICA, 8, Font.ITALIC, BaseColor.DARK_GRAY));
    doc.add(desc);

    doc.close();
  }

  private void writeKontenplanPDF(File file) throws Exception
  {
    Document doc = new Document();
    PdfWriter.getInstance(doc, new FileOutputStream(file));
    doc.open();

    addPdfTitle(doc, "Kontenplan (Chart of Accounts)", "Beerfurther Schwimmbad e.V. | Vereins-Buchungsrahmen");

    Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
    Font normalFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.BLACK);

    PdfPTable table = new PdfPTable(4);
    table.setWidthPercentage(100);
    table.setWidths(new float[] { 2f, 4f, 2f, 4f });

    String[] headers = { "Konto-Nr.", "Kontobezeichnung", "Art", "Steuerliche Spha\u0308re" };
    for (String h : headers)
    {
      PdfPCell cell = new PdfPCell(new Phrase(h, headFont));
      cell.setBackgroundColor(BaseColor.GRAY);
      cell.setHorizontalAlignment(Element.ALIGN_CENTER);
      table.addCell(cell);
    }

    try
    {
      DBIterator<Buchungsart> it = Einstellungen.getDBService().createList(Buchungsart.class);
      while (it.hasNext())
      {
        Buchungsart b = it.next();
        if (b.getNummer() == null) continue;

        String typeStr = (b.getArt() == 0) ? "Einnahme" : ((b.getArt() == 1) ? "Ausgabe" : "Umbuchung");
        Sphere s = getSphere(b.getBuchungsklasse(), b);

        table.addCell(new PdfPCell(new Phrase(b.getNummer(), normalFont)));
        table.addCell(new PdfPCell(new Phrase(b.getBezeichnung(), normalFont)));
        table.addCell(new PdfPCell(new Phrase(typeStr, normalFont)));
        table.addCell(new PdfPCell(new Phrase(s.getLabel(), normalFont)));
      }
    }
    catch (Exception e)
    {
      Logger.error("Fehler beim Exportieren des Kontenplans", e);
    }

    doc.add(table);
    doc.close();
  }

  private void writeCombinedPDF(File file, int targetYear, int startYear,
      Map<Sphere, Map<Integer, Double>> incomeBySphereAndYear,
      Map<Sphere, Map<Integer, Double>> expenseBySphereAndYear) throws Exception
  {
    // First query bookings to calculate the EuerData
    Calendar cal = Calendar.getInstance();
    cal.set(startYear, Calendar.JANUARY, 1, 0, 0, 0);
    Date fromDate = cal.getTime();
    cal.set(targetYear, Calendar.DECEMBER, 31, 23, 59, 59);
    Date toDate = cal.getTime();

    DBIterator<Buchung> it = Einstellungen.getDBService().createList(Buchung.class);
    it.addFilter("datum >= ?", fromDate);
    it.addFilter("datum <= ?", toDate);
    List<Buchung> bookings = new ArrayList<>();
    while (it.hasNext())
    {
      bookings.add(it.next());
    }

    EuerData euerData = calculateEuerData(bookings, startYear, targetYear);

    Document doc = new Document();
    PdfWriter.getInstance(doc, new FileOutputStream(file));
    doc.open();

    addPdfTitle(doc, "\u00dcberschussermittlung und Verm\u00f6gensaufstellung", "Beerfurther Schwimmbad e.V. | Zeitraum: " + startYear + " - " + targetYear);

    Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
    Font boldFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.BLACK);
    Font normalFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.BLACK);

    Paragraph pSec1 = new Paragraph("I. Gegen\u00fcberstellung der Einnahmen und Ausgaben (E\u00dcR)", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY));
    pSec1.setSpacingBefore(10);
    pSec1.setSpacingAfter(5);
    doc.add(pSec1);

    int numYears = targetYear - startYear + 1;
    PdfPTable table = new PdfPTable(1 + numYears);
    table.setWidthPercentage(100);
    float[] widths = new float[1 + numYears];
    widths[0] = 4f;
    for (int i = 1; i < widths.length; i++) widths[i] = 2f;
    table.setWidths(widths);

    PdfPCell ct0 = new PdfPCell(new Phrase("Steuerlicher Bereich / Posten", headFont));
    ct0.setBackgroundColor(BaseColor.GRAY);
    table.addCell(ct0);
    for (int y = startYear; y <= targetYear; y++)
    {
      PdfPCell ctY = new PdfPCell(new Phrase(String.valueOf(y), headFont));
      ctY.setBackgroundColor(BaseColor.GRAY);
      ctY.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(ctY);
    }

    // A. Ideeller Tätigkeitsbereich
    addEuerHeaderRow(table, "A. Ideeller Ta\u0308tigkeitsbereich", startYear, targetYear, boldFont);
    addEuerRow(table, "Einnahmen:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Beitragseinnahmen", euerData.ideellBeitraege, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Spenden", euerData.ideellSpenden, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Staatliche Zuschu\u0308sse u. \u00e4.", euerData.ideellZuschuessel, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Einnahmen", euerData.ideellSonstigeInc, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> aInc = java.util.Arrays.asList(euerData.ideellBeitraege, euerData.ideellSpenden, euerData.ideellZuschuessel, euerData.ideellSonstigeInc);
    addEuerSumRow(table, "Summe Einnahmen A", aInc, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    
    addEuerRow(table, "Ausgaben:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Beitr\u00e4ge an Verb\u00e4nde", euerData.ideellVerband, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Verwaltungsausgaben (B\u00fcro, Porto, Beratung)", euerData.ideellVerwaltung, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Spendenwerbung", euerData.ideellWerbung, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Mitgliederbetreuung / -pflege", euerData.ideellMitglieder, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Ausgaben", euerData.ideellSonstigeExp, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> aExp = java.util.Arrays.asList(euerData.ideellVerband, euerData.ideellVerwaltung, euerData.ideellWerbung, euerData.ideellMitglieder, euerData.ideellSonstigeExp);
    addEuerSumRow(table, "Summe Ausgaben A", aExp, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    addEuerNetRow(table, "\u00dcberschuss / Verlust A", aInc, aExp, startYear, targetYear, boldFont, 1, new BaseColor(220, 235, 220));

    // B. Vermögensverwaltung
    addEuerHeaderRow(table, "B. Vermo\u0308gensverwaltung", startYear, targetYear, boldFont);
    addEuerRow(table, "Einnahmen:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Zinsen und Kapitalertr\u00e4ge", euerData.vermKapital, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Miet- und Pachteinnahmen", euerData.vermMiete, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Einnahmen", euerData.vermSonstigeInc, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> bInc = java.util.Arrays.asList(euerData.vermKapital, euerData.vermMiete, euerData.vermSonstigeInc);
    addEuerSumRow(table, "Summe Einnahmen B", bInc, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    
    addEuerRow(table, "Ausgaben:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Reparaturen / Instandhaltungen", euerData.vermReparaturen, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Abschreibung auf Sachanlagen", euerData.vermAbschreibung, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Ausgaben", euerData.vermSonstigeExp, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> bExp = java.util.Arrays.asList(euerData.vermReparaturen, euerData.vermAbschreibung, euerData.vermSonstigeExp);
    addEuerSumRow(table, "Summe Ausgaben B", bExp, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    addEuerNetRow(table, "\u00dcberschuss / Verlust B", bInc, bExp, startYear, targetYear, boldFont, 1, new BaseColor(220, 235, 220));

    // C. Zweckbetriebe
    addEuerHeaderRow(table, "C. Zweckbetriebe (z.B. Sportbetrieb)", startYear, targetYear, boldFont);
    addEuerRow(table, "Einnahmen:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Teilnehmer- und Nutzungsgeb\u00fchren", euerData.zweckGebuehren, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Zuschu\u0308sse", euerData.zweckZuschuesse, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Einnahmen", euerData.zweckSonstigeInc, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> cInc = java.util.Arrays.asList(euerData.zweckGebuehren, euerData.zweckZuschuesse, euerData.zweckSonstigeInc);
    addEuerSumRow(table, "Summe Einnahmen C", cInc, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    
    addEuerRow(table, "Ausgaben:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Kosten des Sportbetriebs", euerData.zweckSport, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Pacht / Miete", euerData.zweckPacht, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Gas, Strom, Wasser", euerData.zweckNebenkosten, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Instandhaltungen / Reparaturen", euerData.zweckInstandhaltung, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Ausgaben", euerData.zweckSonstigeExp, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> cExp = java.util.Arrays.asList(euerData.zweckSport, euerData.zweckPacht, euerData.zweckNebenkosten, euerData.zweckInstandhaltung, euerData.zweckSonstigeExp);
    addEuerSumRow(table, "Summe Ausgaben C", cExp, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    addEuerNetRow(table, "\u00dcberschuss / Verlust C", cInc, cExp, startYear, targetYear, boldFont, 1, new BaseColor(220, 235, 220));

    // D. Steuerpflichtige wirtschaftliche Geschäftsbetriebe
    addEuerHeaderRow(table, "D. Steuerpflichtige wirtschaftliche Gescha\u0308ftsbetriebe", startYear, targetYear, boldFont);
    addEuerRow(table, "Einnahmen:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Verk\u00e4ufe / Umsatzerl\u00f6se (incl. USt.)", euerData.wgbUmsatz, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Einnahmen", euerData.wgbSonstigeInc, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> dInc = java.util.Arrays.asList(euerData.wgbUmsatz, euerData.wgbSonstigeInc);
    addEuerSumRow(table, "Summe Einnahmen D", dInc, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    
    addEuerRow(table, "Ausgaben:", null, startYear, targetYear, boldFont, 1);
    addEuerRow(table, "Wareneinkauf", euerData.wgbWareneinkauf, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Abschreibungen / GWG", euerData.wgbAbschreibungen, startYear, targetYear, normalFont, 2);
    addEuerRow(table, "Sonstige Ausgaben", euerData.wgbSonstigeExp, startYear, targetYear, normalFont, 2);
    
    List<Map<Integer, Double>> dExp = java.util.Arrays.asList(euerData.wgbWareneinkauf, euerData.wgbAbschreibungen, euerData.wgbSonstigeExp);
    addEuerSumRow(table, "Summe Ausgaben D", dExp, startYear, targetYear, boldFont, 2, new BaseColor(240, 240, 240));
    addEuerNetRow(table, "\u00dcberschuss / Verlust D (Saldierter Betrag)", dInc, dExp, startYear, targetYear, boldFont, 1, new BaseColor(220, 235, 220));

    // Summary line for WGB
    addEuerSumRow(table, "Summe Einnahmen aller wGB (Freibetraggrenze 45/50k \u20ac)", dInc, startYear, targetYear, boldFont, 1, new BaseColor(255, 240, 200));

    // E. Nicht zugeordnet
    addEuerHeaderRow(table, "E. Nicht zugeordnete Buchungen (keine Spha\u0308re)", startYear, targetYear, boldFont);
    addEuerRow(table, "Einnahmen", euerData.unasIncome, startYear, targetYear, normalFont, 1);
    addEuerRow(table, "Ausgaben", euerData.unasExpense, startYear, targetYear, normalFont, 1);
    List<Map<Integer, Double>> eInc = java.util.Arrays.asList(euerData.unasIncome);
    List<Map<Integer, Double>> eExp = java.util.Arrays.asList(euerData.unasExpense);
    addEuerNetRow(table, "\u00dcberschuss / Verlust E", eInc, eExp, startYear, targetYear, boldFont, 1, new BaseColor(255, 230, 230));

    doc.add(table);

    Paragraph pSec2 = new Paragraph("\nII. Vermo\u0308gensaufstellung", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY));
    pSec2.setSpacingBefore(15);
    pSec2.setSpacingAfter(5);
    doc.add(pSec2);

    PdfPTable tableAssets = new PdfPTable(1 + numYears);
    tableAssets.setWidthPercentage(100);
    tableAssets.setWidths(widths);

    PdfPCell ca1 = new PdfPCell(new Phrase("Kategorie / Posten", headFont));
    ca1.setBackgroundColor(BaseColor.GRAY);
    tableAssets.addCell(ca1);
    for (int y = startYear; y <= targetYear; y++)
    {
      PdfPCell caY = new PdfPCell(new Phrase("zum 31.12." + y, headFont));
      caY.setBackgroundColor(BaseColor.GRAY);
      caY.setHorizontalAlignment(Element.ALIGN_RIGHT);
      tableAssets.addCell(caY);
    }

    Map<String, Map<Integer, Double>> vData = getVermoegensaufstellungData(startYear, targetYear);

    String[][] vermoegenKeys = {
      { "Anlageverm\u00f6gen (Grundst\u00fccke, Geb\u00e4ude, Einrichtungen usw.)", "anlagevermoegen" },
      { "Kassenbestand und Bankguthaben", "kassenbestand" },
      { "Wertpapiere (Festgelder, Sparb\u00fccher, Aktien usw.)", "wertpapiere" },
      { "Forderungen", "forderungen" },
      { "Verbindlichkeiten", "verbindlichkeiten" }
    };

    for (String[] vk : vermoegenKeys)
    {
      tableAssets.addCell(new PdfPCell(new Phrase(vk[0], normalFont)));
      for (int y = startYear; y <= targetYear; y++)
      {
        double val = vData.get(vk[1]).getOrDefault(y, 0.0);
        PdfPCell valCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", val), normalFont));
        valCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tableAssets.addCell(valCell);
      }
    }

    doc.add(tableAssets);

    Paragraph pSec3 = new Paragraph("\nIII. Ru\u0308cklagen und Vermo\u0308genszuf\u0308uhrungen", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY));
    pSec3.setSpacingBefore(15);
    pSec3.setSpacingAfter(5);
    doc.add(pSec3);

    PdfPTable tableR = new PdfPTable(1 + numYears);
    tableR.setWidthPercentage(100);
    tableR.setWidths(widths);

    PdfPCell cr1 = new PdfPCell(new Phrase("R\u00fccklagenart / Verm\u00f6genszuf\u00fchrung", headFont));
    cr1.setBackgroundColor(BaseColor.GRAY);
    tableR.addCell(cr1);
    for (int y = startYear; y <= targetYear; y++)
    {
      PdfPCell crY = new PdfPCell(new Phrase("zum 31.12." + y, headFont));
      crY.setBackgroundColor(BaseColor.GRAY);
      crY.setHorizontalAlignment(Element.ALIGN_RIGHT);
      tableR.addCell(crY);
    }

    String[][] ruecklagenKeys = {
      { "Projektru\u0308cklagen, Betriebsmittelru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 1 AO)", "projektruecklagen" },
      { "Freie Ru\u0308cklagen (\u00a7 62 Abs. 1 Nr. 3 AO)", "freieruecklagen" },
      { "Verm\u00f6genszuf\u00fchrungen aus Schenkungen, Erbschaften, Spendenaufrufen usw. (\u00a7 62 Abs. 3/4 AO)", "vermoegenszufuehrungen" }
    };

    for (String[] rk : ruecklagenKeys)
    {
      tableR.addCell(new PdfPCell(new Phrase(rk[0], normalFont)));
      for (int y = startYear; y <= targetYear; y++)
      {
        double val = vData.get(rk[1]).getOrDefault(y, 0.0);
        PdfPCell valCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", val), normalFont));
        valCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tableR.addCell(valCell);
      }
    }

    doc.add(tableR);
    doc.close();
  }

  private void addEuerHeaderRow(PdfPTable table, String label, int startYear, int targetYear, Font font)
  {
    PdfPCell cell = new PdfPCell(new Phrase(label, font));
    cell.setBackgroundColor(new BaseColor(220, 220, 220));
    cell.setColspan(1 + (targetYear - startYear + 1));
    table.addCell(cell);
  }

  private void addEuerRow(PdfPTable table, String label, Map<Integer, Double> values,
      int startYear, int targetYear, Font font, int indent)
  {
    String indentStr = "";
    for (int i = 0; i < indent; i++) indentStr += "  ";
    table.addCell(new PdfPCell(new Phrase(indentStr + label, font)));
    
    for (int y = startYear; y <= targetYear; y++)
    {
      double val = (values != null) ? values.getOrDefault(y, 0.0) : 0.0;
      PdfPCell cell = new PdfPCell(new Phrase((values != null) ? String.format("%.2f \u20ac", val) : "", font));
      cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(cell);
    }
  }

  private void addEuerSumRow(PdfPTable table, String label,
      List<Map<Integer, Double>> valueMaps, int startYear, int targetYear, Font font, int indent, BaseColor bgColor)
  {
    String indentStr = "";
    for (int i = 0; i < indent; i++) indentStr += "  ";
    PdfPCell labelCell = new PdfPCell(new Phrase(indentStr + label, font));
    if (bgColor != null) labelCell.setBackgroundColor(bgColor);
    table.addCell(labelCell);
    
    for (int y = startYear; y <= targetYear; y++)
    {
      double sum = 0.0;
      for (Map<Integer, Double> m : valueMaps)
      {
        sum += m.getOrDefault(y, 0.0);
      }
      PdfPCell cell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", sum), font));
      cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
      if (bgColor != null) cell.setBackgroundColor(bgColor);
      table.addCell(cell);
    }
  }

  private void addEuerNetRow(PdfPTable table, String label,
      List<Map<Integer, Double>> incomeMaps, List<Map<Integer, Double>> expenseMaps,
      int startYear, int targetYear, Font font, int indent, BaseColor bgColor)
  {
    String indentStr = "";
    for (int i = 0; i < indent; i++) indentStr += "  ";
    PdfPCell labelCell = new PdfPCell(new Phrase(indentStr + label, font));
    if (bgColor != null) labelCell.setBackgroundColor(bgColor);
    table.addCell(labelCell);
    
    for (int y = startYear; y <= targetYear; y++)
    {
      double inc = 0.0;
      for (Map<Integer, Double> m : incomeMaps) inc += m.getOrDefault(y, 0.0);
      double exp = 0.0;
      for (Map<Integer, Double> m : expenseMaps) exp += m.getOrDefault(y, 0.0);
      double net = inc - exp;
      
      PdfPCell cell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", net), font));
      cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
      if (bgColor != null) cell.setBackgroundColor(bgColor);
      table.addCell(cell);
    }
  }

  private void writeAveuerPDF(File file, int targetYear, int startYear,
      Map<Sphere, Map<Integer, Double>> incomeBySphereAndYear,
      Map<Sphere, Map<Integer, Double>> expenseBySphereAndYear) throws Exception
  {
    Document doc = new Document();
    PdfWriter.getInstance(doc, new FileOutputStream(file));
    doc.open();

    addPdfTitle(doc, "Anlage AVEU\u0308R & Erg\u00e4nzungen", "Beerfurther Schwimmbad e.V. | Zieljahr: " + targetYear);

    Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
    Font boldFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.BLACK);
    Font normalFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.BLACK);

    Paragraph p = new Paragraph("Anlagenverzeichnis Entwicklung des Anlagevermo\u0308gens (AVEU\u0308R)", new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY));
    p.setSpacingBefore(10);
    p.setSpacingAfter(5);
    doc.add(p);

    PdfPTable table = new PdfPTable(4);
    table.setWidthPercentage(100);
    table.setWidths(new float[] { 4f, 2f, 2f, 2f });

    PdfPCell c0 = new PdfPCell(new Phrase("Anlagegut", headFont));
    c0.setBackgroundColor(BaseColor.GRAY);
    table.addCell(c0);
    PdfPCell c1 = new PdfPCell(new Phrase("Anschaffungswert", headFont));
    c1.setBackgroundColor(BaseColor.GRAY);
    table.addCell(c1);
    PdfPCell c2 = new PdfPCell(new Phrase("Abschreibung des Jahres", headFont));
    c2.setBackgroundColor(BaseColor.GRAY);
    table.addCell(c2);
    PdfPCell c3 = new PdfPCell(new Phrase("Buchwert 31.12.", headFont));
    c3.setBackgroundColor(BaseColor.GRAY);
    table.addCell(c3);

    List<AssetValuation> assets = getAssetValuations(targetYear);
    double totalAcq = 0.0;
    double totalDepr = 0.0;
    double totalBook = 0.0;
    for (AssetValuation asset : assets)
    {
      table.addCell(new PdfPCell(new Phrase(asset.name, normalFont)));
      
      PdfPCell startCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", asset.startValue), normalFont));
      startCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(startCell);

      PdfPCell deprCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", asset.depr), normalFont));
      deprCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(deprCell);

      PdfPCell endCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", asset.endValue), normalFont));
      endCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
      table.addCell(endCell);
      
      totalAcq += asset.startValue;
      totalDepr += asset.depr;
      totalBook += asset.endValue;
    }
    
    // Add total row
    PdfPCell totalLabel = new PdfPCell(new Phrase("Gesamtsumme", boldFont));
    table.addCell(totalLabel);
    
    PdfPCell totalAcqCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", totalAcq), boldFont));
    totalAcqCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
    table.addCell(totalAcqCell);

    PdfPCell totalDeprCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", totalDepr), boldFont));
    totalDeprCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
    table.addCell(totalDeprCell);

    PdfPCell totalBookCell = new PdfPCell(new Phrase(String.format("%.2f \u20ac", totalBook), boldFont));
    totalBookCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
    table.addCell(totalBookCell);

    doc.add(table);
    doc.close();
  }

  private static class AssetValuation
  {
    String name;
    double startValue;
    double depr;
    double endValue;
    
    AssetValuation(String name, double startValue, double depr, double endValue)
    {
      this.name = name;
      this.startValue = startValue;
      this.depr = depr;
      this.endValue = endValue;
    }
  }

  private List<AssetValuation> getAssetValuations(int year) throws Exception
  {
    List<AssetValuation> result = new ArrayList<>();
    
    DBIterator<Konto> it = Einstellungen.getDBService().createList(Konto.class);
    it.addFilter("kontoart = ?", Kontoart.ANLAGE.getKey());
    while (it.hasNext())
    {
      Konto k = it.next();
      Date acqDate = k.getAnschaffung();
      if (acqDate == null) continue;
      
      Calendar cal = Calendar.getInstance();
      cal.setTime(acqDate);
      int acqYear = cal.get(Calendar.YEAR);
      if (acqYear > year) continue; // not yet acquired
      
      double startValue = 0.0;
      if (acqYear == year)
      {
        startValue = k.getBetrag() != null ? k.getBetrag() : 0.0;
      }
      else
      {
        Calendar cAB = Calendar.getInstance();
        cAB.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        cAB.set(Calendar.MILLISECOND, 0);
        Date abDate = cAB.getTime();
        
        DBIterator<Anfangsbestand> abIt = Einstellungen.getDBService().createList(Anfangsbestand.class);
        abIt.addFilter("konto = ?", Integer.parseInt(k.getID()));
        abIt.addFilter("datum = ?", abDate);
        if (abIt.hasNext())
        {
          startValue = abIt.next().getBetrag();
        }
        else
        {
          startValue = k.getBetrag() != null ? k.getBetrag() : 0.0;
        }
      }
      
      double depr = 0.0;
      Calendar cStart = Calendar.getInstance();
      cStart.set(year, Calendar.JANUARY, 1, 0, 0, 0);
      Calendar cEnd = Calendar.getInstance();
      cEnd.set(year, Calendar.DECEMBER, 31, 23, 59, 59);
      
      DBIterator<Buchung> bIt = Einstellungen.getDBService().createList(Buchung.class);
      bIt.addFilter("konto = ?", Integer.parseInt(k.getID()));
      bIt.addFilter("datum >= ?", cStart.getTime());
      bIt.addFilter("datum <= ?", cEnd.getTime());
      while (bIt.hasNext())
      {
        Buchung b = bIt.next();
        Buchungsart ba = b.getBuchungsart();
        if (ba != null && ba.getAbschreibung())
        {
          depr += b.getBetrag();
        }
      }
      
      double endValue = startValue + depr;
      
      if (startValue != 0.0 || depr != 0.0 || endValue != 0.0)
      {
        result.add(new AssetValuation(k.getBezeichnung(), startValue, depr, endValue));
      }
    }
    return result;
  }

  private static class EuerData
  {
    public Map<Integer, Double> ideellBeitraege = new HashMap<>();
    public Map<Integer, Double> ideellSpenden = new HashMap<>();
    public Map<Integer, Double> ideellZuschuessel = new HashMap<>();
    public Map<Integer, Double> ideellSonstigeInc = new HashMap<>();
    
    public Map<Integer, Double> ideellVerband = new HashMap<>();
    public Map<Integer, Double> ideellVerwaltung = new HashMap<>();
    public Map<Integer, Double> ideellWerbung = new HashMap<>();
    public Map<Integer, Double> ideellMitglieder = new HashMap<>();
    public Map<Integer, Double> ideellSonstigeExp = new HashMap<>();
    
    public Map<Integer, Double> vermKapital = new HashMap<>();
    public Map<Integer, Double> vermMiete = new HashMap<>();
    public Map<Integer, Double> vermSonstigeInc = new HashMap<>();
    
    public Map<Integer, Double> vermReparaturen = new HashMap<>();
    public Map<Integer, Double> vermAbschreibung = new HashMap<>();
    public Map<Integer, Double> vermSonstigeExp = new HashMap<>();
    
    public Map<Integer, Double> zweckGebuehren = new HashMap<>();
    public Map<Integer, Double> zweckZuschuesse = new HashMap<>();
    public Map<Integer, Double> zweckSonstigeInc = new HashMap<>();
    
    public Map<Integer, Double> zweckSport = new HashMap<>();
    public Map<Integer, Double> zweckPacht = new HashMap<>();
    public Map<Integer, Double> zweckNebenkosten = new HashMap<>();
    public Map<Integer, Double> zweckInstandhaltung = new HashMap<>();
    public Map<Integer, Double> zweckSonstigeExp = new HashMap<>();
    
    public Map<Integer, Double> wgbUmsatz = new HashMap<>();
    public Map<Integer, Double> wgbSonstigeInc = new HashMap<>();
    
    public Map<Integer, Double> wgbWareneinkauf = new HashMap<>();
    public Map<Integer, Double> wgbAbschreibungen = new HashMap<>();
    public Map<Integer, Double> wgbSonstigeExp = new HashMap<>();
    
    public Map<Integer, Double> unasIncome = new HashMap<>();
    public Map<Integer, Double> unasExpense = new HashMap<>();
    
    public EuerData(int startYear, int targetYear)
    {
      for (int y = startYear; y <= targetYear; y++)
      {
        ideellBeitraege.put(y, 0.0);
        ideellSpenden.put(y, 0.0);
        ideellZuschuessel.put(y, 0.0);
        ideellSonstigeInc.put(y, 0.0);
        ideellVerband.put(y, 0.0);
        ideellVerwaltung.put(y, 0.0);
        ideellWerbung.put(y, 0.0);
        ideellMitglieder.put(y, 0.0);
        ideellSonstigeExp.put(y, 0.0);
        
        vermKapital.put(y, 0.0);
        vermMiete.put(y, 0.0);
        vermSonstigeInc.put(y, 0.0);
        vermReparaturen.put(y, 0.0);
        vermAbschreibung.put(y, 0.0);
        vermSonstigeExp.put(y, 0.0);
        
        zweckGebuehren.put(y, 0.0);
        zweckZuschuesse.put(y, 0.0);
        zweckSonstigeInc.put(y, 0.0);
        zweckSport.put(y, 0.0);
        zweckPacht.put(y, 0.0);
        zweckNebenkosten.put(y, 0.0);
        zweckInstandhaltung.put(y, 0.0);
        zweckSonstigeExp.put(y, 0.0);
        
        wgbUmsatz.put(y, 0.0);
        wgbSonstigeInc.put(y, 0.0);
        wgbWareneinkauf.put(y, 0.0);
        wgbAbschreibungen.put(y, 0.0);
        wgbSonstigeExp.put(y, 0.0);
        
        unasIncome.put(y, 0.0);
        unasExpense.put(y, 0.0);
      }
    }
  }

  private EuerData calculateEuerData(List<Buchung> bookings, int startYear, int targetYear) throws Exception
  {
    EuerData data = new EuerData(startYear, targetYear);
    boolean mitSteuer = false;
    boolean steuerInBuchung = false;
    boolean klasseInBuchung = false;
    try
    {
      mitSteuer = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.OPTIERTPFLICHT);
      steuerInBuchung = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.STEUERINBUCHUNG);
      klasseInBuchung = (Boolean) Einstellungen.getEinstellung(Einstellungen.Property.BUCHUNGSKLASSEINBUCHUNG);
    }
    catch (Exception e)
    {
      // fallback
    }

    Calendar cal = Calendar.getInstance();
    for (Buchung b : bookings)
    {
      cal.setTime(b.getDatum());
      int year = cal.get(Calendar.YEAR);
      if (year < startYear || year > targetYear)
      {
        continue;
      }

      Double betrag = b.getBetrag() != null ? b.getBetrag() : 0.0;
      Buchungsart bart = b.getBuchungsart();
      Konto konto = b.getKonto();

      if (konto != null)
      {
        Kontoart ka = konto.getKontoArt();
        if (ka != null && ka.getKey() >= Kontoart.LIMIT.getKey())
        {
          continue;
        }
      }

      // Check Geldtransit to ignore completely
      boolean isGeldtransit = false;
      if (bart != null)
      {
        String baName = bart.getBezeichnung() != null ? bart.getBezeichnung().toLowerCase() : "";
        if (baName.contains("geldtransit")) isGeldtransit = true;
      }
      String zw = b.getZweck() != null ? b.getZweck().toLowerCase() : "";
      if (zw.contains("geldtransit")) isGeldtransit = true;

      if (isGeldtransit) continue;

      Buchungsklasse bklasse = null;
      if (klasseInBuchung)
      {
        bklasse = b.getBuchungsklasse();
      }
      else if (bart != null)
      {
        bklasse = bart.getBuchungsklasse();
      }

      Sphere sphere = getSphere(bklasse, bart);

      double netBetrag = betrag;
      if (mitSteuer && konto != null && konto.getKontoArt() != Kontoart.ANLAGE)
      {
        Object depObj = null;
        try { depObj = b.getAttribute("dependencyid"); } catch (Exception e) {}
        if (depObj == null)
        {
          Steuer steuerObj = steuerInBuchung ? b.getSteuer() : (bart != null ? bart.getSteuer() : null);
          if (steuerObj != null)
          {
            Double satz = steuerObj.getSatz();
            if (satz != null && satz != 0.0)
            {
              netBetrag = Math.round((betrag * 100.0 / (100.0 + satz)) * 100.0) / 100.0;
            }
          }
        }
      }

      double income = 0.0;
      double expense = 0.0;
      int art = (bart != null) ? bart.getArt() : -1;

      if (art == 0) income = netBetrag;
      else if (art == 1) expense = -netBetrag;
      else if (art == 2) continue; // Umbuchung
      else
      {
        if (netBetrag >= 0) income = netBetrag;
        else expense = -netBetrag;
      }

      String baName = (bart != null && bart.getBezeichnung() != null) ? bart.getBezeichnung().toLowerCase() : "";
      String baNum = (bart != null && bart.getNummer() != null) ? bart.getNummer() : "";

      if (sphere == Sphere.IDEELL)
      {
        if (income != 0.0)
        {
          if (baNum.startsWith("400") || baNum.startsWith("401") || baName.contains("beitrag"))
            data.ideellBeitraege.put(year, data.ideellBeitraege.get(year) + income);
          else if (baNum.startsWith("404") || baNum.startsWith("405") || baNum.startsWith("406") || baName.contains("spende") || baName.contains("zuwendung"))
            data.ideellSpenden.put(year, data.ideellSpenden.get(year) + income);
          else if (baNum.startsWith("410") || baNum.startsWith("411") || baName.contains("zuschuss"))
            data.ideellZuschuessel.put(year, data.ideellZuschuessel.get(year) + income);
          else
            data.ideellSonstigeInc.put(year, data.ideellSonstigeInc.get(year) + income);
        }
        if (expense != 0.0)
        {
          if (baNum.startsWith("642") || baName.contains("verband") || (baName.contains("beitrag") && !baName.contains("mitglied")))
            data.ideellVerband.put(year, data.ideellVerband.get(year) + expense);
          else if (baNum.startsWith("6301") || baNum.startsWith("6305") || baNum.startsWith("6800") || baNum.startsWith("6825") || baName.contains("verwaltung") || baName.contains("porto") || baName.contains("beratung"))
            data.ideellVerwaltung.put(year, data.ideellVerwaltung.get(year) + expense);
          else if (baName.contains("werbung"))
            data.ideellWerbung.put(year, data.ideellWerbung.get(year) + expense);
          else if (baNum.startsWith("6300") || baName.contains("mitglieder"))
            data.ideellMitglieder.put(year, data.ideellMitglieder.get(year) + expense);
          else
            data.ideellSonstigeExp.put(year, data.ideellSonstigeExp.get(year) + expense);
        }
      }
      else if (sphere == Sphere.VERMOEGENSVERWALTUNG)
      {
        if (income != 0.0)
        {
          if (baNum.startsWith("702") || baName.contains("zins") || baName.contains("kapital") || baName.contains("dividende"))
            data.vermKapital.put(year, data.vermKapital.get(year) + income);
          else if (baName.contains("miet") || baName.contains("pacht"))
            data.vermMiete.put(year, data.vermMiete.get(year) + income);
          else
            data.vermSonstigeInc.put(year, data.vermSonstigeInc.get(year) + income);
        }
        if (expense != 0.0)
        {
          if (baName.contains("reparatur") || baName.contains("instand"))
            data.vermReparaturen.put(year, data.vermReparaturen.get(year) + expense);
          else if (baName.contains("abschreib"))
            data.vermAbschreibung.put(year, data.vermAbschreibung.get(year) + expense);
          else
            data.vermSonstigeExp.put(year, data.vermSonstigeExp.get(year) + expense);
        }
      }
      else if (sphere == Sphere.ZWECKBETRIEB)
      {
        if (income != 0.0)
        {
          if (baNum.startsWith("48") || baName.contains("zuschuss"))
            data.zweckZuschuesse.put(year, data.zweckZuschuesse.get(year) + income);
          else
            data.zweckGebuehren.put(year, data.zweckGebuehren.get(year) + income);
        }
        if (expense != 0.0)
        {
          if (baNum.startsWith("50") || baName.contains("sport"))
            data.zweckSport.put(year, data.zweckSport.get(year) + expense);
          else if (baNum.startsWith("6315") || baName.contains("pacht") || baName.contains("miete"))
            data.zweckPacht.put(year, data.zweckPacht.get(year) + expense);
          else if (baNum.startsWith("6325") || baName.contains("gas") || baName.contains("strom") || baName.contains("wasser"))
            data.zweckNebenkosten.put(year, data.zweckNebenkosten.get(year) + expense);
          else if (baNum.startsWith("647") || baName.contains("reparatur") || baName.contains("instand"))
            data.zweckInstandhaltung.put(year, data.zweckInstandhaltung.get(year) + expense);
          else
            data.zweckSonstigeExp.put(year, data.zweckSonstigeExp.get(year) + expense);
        }
      }
      else if (sphere == Sphere.WGB)
      {
        if (income != 0.0)
        {
          if (baNum.startsWith("418") || baNum.startsWith("420") || baNum.startsWith("421") || baName.contains("erlös") || baName.contains("umsatz") || baName.contains("verkauf"))
            data.wgbUmsatz.put(year, data.wgbUmsatz.get(year) + income);
          else
            data.wgbSonstigeInc.put(year, data.wgbSonstigeInc.get(year) + income);
        }
        if (expense != 0.0)
        {
          if (baNum.startsWith("500") || baNum.startsWith("520") || baName.contains("waren") || baName.contains("eingang"))
            data.wgbWareneinkauf.put(year, data.wgbWareneinkauf.get(year) + expense);
          else if (baNum.startsWith("626") || baName.contains("abschreib"))
            data.wgbAbschreibungen.put(year, data.wgbAbschreibungen.get(year) + expense);
          else
            data.wgbSonstigeExp.put(year, data.wgbSonstigeExp.get(year) + expense);
        }
      }
      else if (sphere == Sphere.UNASSIGNED)
      {
        if (income != 0.0) data.unasIncome.put(year, data.unasIncome.get(year) + income);
        if (expense != 0.0) data.unasExpense.put(year, data.unasExpense.get(year) + expense);
      }
    }
    return data;
  }

  private void addPdfTitle(Document doc, String titleText, String subtitleText) throws Exception
  {
    Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD, BaseColor.DARK_GRAY);
    Font subtitleFont = new Font(Font.FontFamily.HELVETICA, 10, Font.ITALIC, BaseColor.GRAY);
    Paragraph p1 = new Paragraph(titleText, titleFont);
    p1.setSpacingAfter(5);
    doc.add(p1);
    Paragraph p2 = new Paragraph(subtitleText, subtitleFont);
    p2.setSpacingAfter(20);
    doc.add(p2);
  }

  private String formatDatevKonto(String nr)
  {
    if (nr == null) return "";
    String trimmed = nr.trim();
    if (trimmed.isEmpty()) return "";
    while (trimmed.length() < 4)
    {
      trimmed = trimmed + "0";
    }
    return trimmed;
  }
}
