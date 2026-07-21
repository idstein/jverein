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

  private Settings settings;
  private SelectInput targetYearInput;

  // UI Panels
  private Text warnungenText;
  private Table missingBelegeTable;
  private Table ergebnisseTable;
  private Text exportLogsText;

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
    lbl.setText("Steuer-Zieljahr wählen:");

    List<Integer> years = new ArrayList<>();
    int currentYear = Calendar.getInstance().get(Calendar.YEAR);
    for (int y = currentYear; y >= currentYear - 5; y--)
    {
      years.add(y);
    }

    int savedYear = settings.getInt("target_year", currentYear - 1);
    targetYearInput = new SelectInput(years, savedYear);
    targetYearInput.addListener(evt -> {
      if (evt != null)
      {
        try
        {
          settings.setAttribute("target_year", (Integer) targetYearInput.getValue());
          refreshAudits();
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

    Group gr = new Group(parent, SWT.NONE);
    gr.setText("Prüfungsergebnisse & Frühwarnungen");
    gr.setLayout(new GridLayout(1, false));
    gr.setLayoutData(new GridData(GridData.FILL_BOTH));

    warnungenText = new Text(gr, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
    warnungenText.setLayoutData(new GridData(GridData.FILL_BOTH));
    warnungenText.setText("Audits werden geladen...");
  }

  public void paintBelegeTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    // Checklist Group
    Group checklistGroup = new Group(parent, SWT.NONE);
    checklistGroup.setText("Nachweise & Externe Dokumenten-Checkliste");
    checklistGroup.setLayout(new GridLayout(2, false));
    checklistGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

    taetigkeitsberichtCb = new CheckboxInput(settings.getBoolean("cb_taetigkeit", false));
    taetigkeitsberichtCb.addListener(e -> settings.setAttribute("cb_taetigkeit", (Boolean) taetigkeitsberichtCb.getValue()));
    Label l1 = new Label(checklistGroup, SWT.NONE);
    l1.setText("Tätigkeitsbericht (inhaltliche Tätigkeitsbeschreibung der 3 Jahre)");
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

    zerRegisterCb = new CheckboxInput(settings.getBoolean("cb_zer", false));
    zerRegisterCb.addListener(e -> settings.setAttribute("cb_zer", (Boolean) zerRegisterCb.getValue()));
    Label l4 = new Label(checklistGroup, SWT.NONE);
    l4.setText("Prüfung Zuwendungsempfängerregister (BfSt) auf Aktualität");
    zerRegisterCb.paint(checklistGroup);

    verzichtserklaerungCb = new CheckboxInput(settings.getBoolean("cb_verzicht", false));
    verzichtserklaerungCb.addListener(e -> settings.setAttribute("cb_verzicht", (Boolean) verzichtserklaerungCb.getValue()));
    Label l5 = new Label(checklistGroup, SWT.NONE);
    l5.setText("Schriftliche Verzichtserklärungen / Verträge für alle Aufwandsspenden");
    verzichtserklaerungCb.paint(checklistGroup);

    // Missing Belege Table Group
    Group tableGroup = new Group(parent, SWT.NONE);
    tableGroup.setText("Fehlende digitale Belege im Steuerzeitraum (3 Jahre)");
    tableGroup.setLayout(new GridLayout(1, false));
    tableGroup.setLayoutData(new GridData(GridData.FILL_BOTH));

    missingBelegeTable = new Table(tableGroup, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    missingBelegeTable.setHeaderVisible(true);
    missingBelegeTable.setLinesVisible(true);
    missingBelegeTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    String[] columns = {"Datum", "Buchungsart", "Name / Empfänger", "Zweck", "Betrag"};
    int[] widths = {80, 150, 150, 200, 80};
    for (int i = 0; i < columns.length; i++)
    {
      TableColumn col = new TableColumn(missingBelegeTable, SWT.LEFT);
      col.setText(columns[i]);
      col.setWidth(widths[i]);
    }
  }

  public void paintErgebnisseTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    Group gr = new Group(parent, SWT.NONE);
    gr.setText("Ergebnisse nach steuerrechtlichen Sphären");
    gr.setLayout(new GridLayout(1, false));
    gr.setLayoutData(new GridData(GridData.FILL_BOTH));

    ergebnisseTable = new Table(gr, SWT.BORDER | SWT.FULL_SELECTION);
    ergebnisseTable.setHeaderVisible(true);
    ergebnisseTable.setLinesVisible(true);
    ergebnisseTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    TableColumn col1 = new TableColumn(ergebnisseTable, SWT.LEFT);
    col1.setText("Sphäre / Jahr");
    col1.setWidth(200);

    TableColumn col2 = new TableColumn(ergebnisseTable, SWT.RIGHT);
    col2.setText("Einnahmen");
    col2.setWidth(120);

    TableColumn col3 = new TableColumn(ergebnisseTable, SWT.RIGHT);
    col3.setText("Ausgaben");
    col3.setWidth(120);

    TableColumn col4 = new TableColumn(ergebnisseTable, SWT.RIGHT);
    col4.setText("Ergebnis");
    col4.setWidth(120);
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

    Button exportBtn = new Button("DATEV-Exportpaket erzeugen (ZIP)", new Action()
    {
      @Override
      public void handleAction(Object context) throws ApplicationException
      {
        try
        {
          generateDatevExportPackage();
        }
        catch (Exception e)
        {
          Logger.error("Fehler beim DATEV Export", e);
          throw new ApplicationException("Fehler beim Erzeugen des DATEV Exports: " + e.getMessage());
        }
      }
    }, null, false, "document-save.png");
    exportBtn.paint(btnComp);

    exportLogsText = new Text(gr, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
    exportLogsText.setLayoutData(new GridData(GridData.FILL_BOTH));
    exportLogsText.setText("Bereit für Export...");

    // Trigger initial audit load
    refreshAudits();
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
          refreshAudits();
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

  private void refreshAudits() throws Exception
  {
    if (targetYearInput == null || targetYearInput.getValue() == null)
    {
      return;
    }
    int targetYear = (Integer) targetYearInput.getValue();
    int startYear = targetYear - 2;

    Calendar cal = Calendar.getInstance();
    cal.set(startYear, Calendar.JANUARY, 1, 0, 0, 0);
    Date fromDate = cal.getTime();
    cal.set(targetYear, Calendar.DECEMBER, 31, 23, 59, 59);
    Date toDate = cal.getTime();

    // Query bookings in 3-year range
    DBIterator<Buchung> it = Einstellungen.getDBService().createList(Buchung.class);
    it.addFilter("datum >= ?", fromDate);
    it.addFilter("datum <= ?", toDate);

    List<Buchung> bookings = new ArrayList<>();
    while (it.hasNext())
    {
      bookings.add(it.next());
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

    // Compute audits & warnings
    StringBuilder warningsSb = new StringBuilder();
    warningsSb.append("Steuerprüfung für den 3-Jahres-Turnus: ").append(startYear).append(" - ").append(targetYear).append("\n");
    warningsSb.append("=========================================================================\n\n");

    // A. Threshold checks (wGB revenue)
    Map<Integer, Double> wgbGrossByYear = new HashMap<>();
    Map<Integer, Double> wgbNetByYear = new HashMap<>();
    Map<Integer, Double> totalRevenueByYear = new HashMap<>();
    
    // Map spheres for calculations
    Map<Sphere, Map<Integer, Double>> incomeBySphereAndYear = new HashMap<>();
    Map<Sphere, Map<Integer, Double>> expenseBySphereAndYear = new HashMap<>();
    
    for (Sphere s : Sphere.values())
    {
      incomeBySphereAndYear.put(s, new HashMap<>());
      expenseBySphereAndYear.put(s, new HashMap<>());
      for (int y = startYear; y <= targetYear; y++)
      {
        incomeBySphereAndYear.get(s).put(y, 0.0);
        expenseBySphereAndYear.get(s).put(y, 0.0);
      }
    }

    // Warning flags
    boolean boardPaymentWarning = false;
    List<Buchung> unassignedBookings = new ArrayList<>();
    List<Buchung> missingBelegeList = new ArrayList<>();
    List<Buchung> largeDonationsWithoutReceipt = new ArrayList<>();
    List<Buchung> reverseChargeBookings = new ArrayList<>();
    double totalReverseChargeBase = 0.0;
    double totalReverseChargeVat = 0.0;

    for (Buchung b : bookings)
    {
      cal.setTime(b.getDatum());
      int year = cal.get(Calendar.YEAR);

      Double betrag = b.getBetrag() != null ? b.getBetrag() : 0.0;
      Buchungsart bart = b.getBuchungsart();
      Buchungsklasse bklasse = bart != null ? bart.getBuchungsklasse() : null;
      Sphere sphere = getSphere(bklasse);

      if (sphere == Sphere.UNASSIGNED)
      {
        unassignedBookings.add(b);
      }

      // Track Einnahmen vs Ausgaben
      if (betrag >= 0)
      {
        Double curr = incomeBySphereAndYear.get(sphere).get(year);
        incomeBySphereAndYear.get(sphere).put(year, curr + betrag);
        
        Double total = totalRevenueByYear.get(year);
        totalRevenueByYear.put(year, (total == null ? 0.0 : total) + betrag);
      }
      else
      {
        Double curr = expenseBySphereAndYear.get(sphere).get(year);
        expenseBySphereAndYear.get(sphere).put(year, curr + Math.abs(betrag));
      }

      if (sphere == Sphere.WGB)
      {
        if (betrag >= 0)
        {
          wgbGrossByYear.put(year, wgbGrossByYear.getOrDefault(year, 0.0) + betrag);
        }
        wgbNetByYear.put(year, wgbNetByYear.getOrDefault(year, 0.0) + betrag);
      }

      // Board compensation check
      String name = b.getName() != null ? b.getName().toLowerCase() : "";
      String zweck = b.getZweck() != null ? b.getZweck().toLowerCase() : "";
      if (name.contains("strawder") || name.contains("beilstein") || name.contains("baumann") 
          || name.contains("bauer") || name.contains("ester") 
          || zweck.contains("vorstand") || zweck.contains("ehrenamtspauschale") 
          || (bart != null && bart.getBezeichnung().toLowerCase().contains("ehrenamtspauschale")))
      {
        // Exclude Alexander Diehl who is a non-board member contractor
        if (!name.contains("diehl"))
        {
          boardPaymentWarning = true;
        }
      }

      // Missing receipt check
      if (!docsByReferenz.containsKey(Long.valueOf(b.getID())))
      {
        missingBelegeList.add(b);
      }

      // Spenden > 300 check
      if (bart != null && Boolean.TRUE.equals(bart.getSpende()) && betrag > 300.0)
      {
        if (b.getSpendenbescheinigung() == null)
        {
          largeDonationsWithoutReceipt.add(b);
        }
      }

      // Reverse Charge check
      boolean isReverseCharge = false;
      if (b.getSteuer() != null && (b.getSteuer().getName().toLowerCase().contains("13b") 
          || b.getSteuer().getName().toLowerCase().contains("reverse")))
      {
        isReverseCharge = true;
      }
      else
      {
        String nameLower = b.getName() != null ? b.getName().toLowerCase() : "";
        String zweckLower = b.getZweck() != null ? b.getZweck().toLowerCase() : "";
        String bartName = bart != null ? bart.getBezeichnung().toLowerCase() : "";
        if (zweckLower.contains("13b") || zweckLower.contains("reverse charge") 
            || bartName.contains("13b") || bartName.contains("reverse charge")
            || nameLower.contains("google") || nameLower.contains("zoom") 
            || nameLower.contains("microsoft") || nameLower.contains("meta") || nameLower.contains("facebook"))
        {
          if (betrag < 0)
          {
            isReverseCharge = true;
          }
        }
      }
      if (isReverseCharge)
      {
        reverseChargeBookings.add(b);
        double base = Math.abs(betrag);
        double rate = 0.19;
        if (b.getSteuer() != null && b.getSteuer().getSatz() != null)
        {
          rate = b.getSteuer().getSatz() / 100.0;
        }
        totalReverseChargeBase += base;
        totalReverseChargeVat += (base * rate);
      }
    }

    // Write wGB warnings
    for (int y = startYear; y <= targetYear; y++)
    {
      double wgbGross = wgbGrossByYear.getOrDefault(y, 0.0);
      double wgbNet = wgbNetByYear.getOrDefault(y, 0.0);
      double limit = (y >= 2024) ? 50000.0 : 45000.0;

      if (wgbGross > limit)
      {
        warningsSb.append("[KRITISCH] Jahr ").append(y).append(": Einnahmen im WGB überschreiten Freibetrag von ")
            .append(limit).append(" € (Brutto-Einnahmen: ").append(wgbGross).append(" €). Steuerpflicht droht!\n");
      }
      else if (wgbGross >= limit * 0.8)
      {
        warningsSb.append("[WARNUNG] Jahr ").append(y).append(": Einnahmen im WGB nahe Freibetraggrenze von ")
            .append(limit).append(" € (Brutto-Einnahmen: ").append(wgbGross).append(" €). Aufmerksam beobachten!\n");
      }

      if (wgbNet < 0)
      {
        warningsSb.append("[KRITISCH] Jahr ").append(y).append(": Wirtschaftlicher Geschäftsbetrieb weist Verlust auf (")
            .append(wgbNet).append(" €). Risiko der Mittelfehlverwendung bei Ausgleich aus ideellem Bereich!\n");
      }
    }

    if (boardPaymentWarning)
    {
      warningsSb.append("[WICHTIG] Vorstandszahlungen festgestellt: Verträge über Ehrenamtspauschale wurden erfasst. ")
          .append("Zwingend die Satzungsermächtigung (§ 27 Abs. 3 BGB) und die Ratifizierung durch die Mitgliederversammlung prüfen!\n");
    }

    if (!largeDonationsWithoutReceipt.isEmpty())
    {
      warningsSb.append("[WARNUNG] ").append(largeDonationsWithoutReceipt.size()).append(" Großspenden (> 300 €) ")
          .append("festgestellt, für die keine formelle Zuwendungsbestätigung erfasst wurde.\n");
    }

    if (!unassignedBookings.isEmpty())
    {
      warningsSb.append("[INFO] ").append(unassignedBookings.size()).append(" Buchungen besitzen keine gültige ")
          .append("Buchungsart oder Buchungsklasse. Bitte vor dem DATEV-Export korrigieren.\n");
    }

    // USt / Reverse Charge section
    warningsSb.append("\n=========================================================================\n");
    warningsSb.append("=== UMSATZSTEUER & § 13b REVERSE CHARGE ===\n");
    warningsSb.append("=========================================================================\n");
    warningsSb.append("Achtung: Auch als Kleinunternehmer (§ 19 UStG) müssen Sie für bezogene Dienstleistungen\n");
    warningsSb.append("ausländischer Unternehmen die Umsatzsteuer nach § 13b UStG anmelden und abführen.\n\n");
    if (reverseChargeBookings.isEmpty())
    {
      warningsSb.append("Keine potenziellen Reverse-Charge-Vorfälle im Zeitraum gefunden.\n");
    }
    else
    {
      warningsSb.append(String.format("Es wurden %d potenzielle Reverse-Charge-Vorfälle identifiziert:\n", reverseChargeBookings.size()));
      SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
      for (Buchung b : reverseChargeBookings)
      {
        double base = Math.abs(b.getBetrag() != null ? b.getBetrag() : 0.0);
        double r = 0.19;
        if (b.getSteuer() != null && b.getSteuer().getSatz() != null)
        {
          r = b.getSteuer().getSatz() / 100.0;
        }
        double vat = base * r;
        warningsSb.append(String.format("  - %s | %s: %.2f € (Satz: %.0f%%) -> USt-Zahllast: %.2f €\n",
            sdf.format(b.getDatum()),
            b.getName() != null ? b.getName() : (b.getZweck() != null ? b.getZweck() : ""),
            base, r * 100.0, vat));
      }
      warningsSb.append(String.format("\nGesamte Bemessungsgrundlage: %.2f €\n", totalReverseChargeBase));
      warningsSb.append(String.format("Abzuführende Umsatzsteuer gesamt: %.2f € (Anzumelden in USt-Erklärung Zeile 94 ff.)\n", totalReverseChargeVat));
    }

    if (warningsSb.length() < 200)
    {
      warningsSb.append("[OK] Keine schwerwiegenden Schwellenwertverletzungen oder Buchungsfehler gefunden.");
    }

    if (warnungenText != null && !warnungenText.isDisposed())
    {
      warnungenText.setText(warningsSb.toString());
    }

    // Populate missing receipts table
    if (missingBelegeTable != null && !missingBelegeTable.isDisposed())
    {
      missingBelegeTable.removeAll();
      SimpleDateFormat sdf = new SimpleDateFormat("dd.mm.yyyy");
      for (Buchung b : missingBelegeList)
      {
        TableItem item = new TableItem(missingBelegeTable, SWT.NONE);
        item.setText(0, b.getDatum() != null ? sdf.format(b.getDatum()) : "");
        item.setText(1, b.getBuchungsart() != null ? b.getBuchungsart().getBezeichnung() : "Ohne Buchungsart");
        item.setText(2, b.getName() != null ? b.getName() : "");
        item.setText(3, b.getZweck() != null ? b.getZweck() : "");
        item.setText(4, b.getBetrag() != null ? String.format("%.2f €", b.getBetrag()) : "");
      }
    }

    // Populate Ergebnisse table
    if (ergebnisseTable != null && !ergebnisseTable.isDisposed())
    {
      ergebnisseTable.removeAll();
      for (Sphere s : Sphere.values())
      {
        TableItem categoryHeader = new TableItem(ergebnisseTable, SWT.NONE);
        categoryHeader.setText(0, s.getLabel());
        categoryHeader.setFont(de.willuhn.jameica.gui.util.Font.BOLD.getSWTFont());
        
        for (int y = startYear; y <= targetYear; y++)
        {
          double inc = incomeBySphereAndYear.get(s).get(y);
          double exp = expenseBySphereAndYear.get(s).get(y);
          double net = inc - exp;

          TableItem item = new TableItem(ergebnisseTable, SWT.NONE);
          item.setText(0, "  - Geschäftsjahr " + y);
          item.setText(1, String.format("%.2f €", inc));
          item.setText(2, String.format("%.2f €", exp));
          item.setText(3, String.format("%.2f €", net));
          
          if (net < 0 && s == Sphere.WGB)
          {
            item.setBackground(GUI.getDisplay().getSystemColor(SWT.COLOR_YELLOW));
          }
        }
      }
    }
  }

  public static Sphere getSphere(Buchungsklasse bk) throws RemoteException
  {
    if (bk == null)
    {
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

  private void generateDatevExportPackage() throws Exception
  {
    if (targetYearInput == null || targetYearInput.getValue() == null)
    {
      return;
    }
    int targetYear = (Integer) targetYearInput.getValue();
    updateExportLogs("Starte DATEV-Exportpaketierung für das Jahr: " + targetYear);

    Calendar cal = Calendar.getInstance();
    cal.set(targetYear, Calendar.JANUARY, 1, 0, 0, 0);
    Date fromDate = cal.getTime();
    cal.set(targetYear, Calendar.DECEMBER, 31, 23, 59, 59);
    Date toDate = cal.getTime();

    // Query bookings
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
      updateExportLogs("Keine Buchungen für " + targetYear + " vorhanden. Export abgebrochen.");
      return;
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

    // Create Zip file in downloads folder
    String userHome = System.getProperty("user.home");
    File downloadsDir = new File(userHome + "/Downloads");
    if (!downloadsDir.exists())
    {
      downloadsDir.mkdirs();
    }
    File zipFile = new File(downloadsDir, "JVerein_DATEV_Export_" + targetYear + ".zip");
    
    updateExportLogs("Erzeuge Archiv unter: " + zipFile.getAbsolutePath());

    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile)))
    {
      // 1. Write EXTF_Buchungsstapel_[Jahr].csv
      zos.putNextEntry(new ZipEntry("EXTF_Buchungsstapel_" + targetYear + ".csv"));
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.ISO_8859_1); // DATEV requires ISO-8859-1 / Windows-1252
      
      // Header for DATEV
      osw.write("EXTF;1.0;1.0;\"Stapel\";1;;;;;;;;\n");
      osw.write("Umsatz;S/H;Konto;Gegenkonto;Belegdatum;Belegfeld 1;Buchungstext;Beleglink\n");

      SimpleDateFormat sdf = new SimpleDateFormat("ddMM");
      StringBuilder xmlContent = new StringBuilder();
      xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      xmlContent.append("<archive xmlns=\"http://xml.datev.de/bedi/tps/document/v030\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"3.0\">\n");
      xmlContent.append("  <header>\n");
      xmlContent.append("    <date>").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date())).append("</date>\n");
      xmlContent.append("    <description>JVerein Belegtransfer Export</description>\n");
      xmlContent.append("  </header>\n");
      xmlContent.append("  <content>\n");

      int fileCounter = 1;
      for (Buchung b : bookings)
      {
        Double betrag = b.getBetrag() != null ? b.getBetrag() : 0.0;
        String sh = (betrag >= 0) ? "S" : "H";
        double absVal = Math.abs(betrag);
        
        Buchungsart bart = b.getBuchungsart();
        String konto = bart != null ? bart.getNummer() : "";
        String gegenkonto = b.getKonto() != null ? b.getKonto().getNummer() : "";
        
        String dateStr = b.getDatum() != null ? sdf.format(b.getDatum()) : "";
        String text = b.getZweck() != null ? b.getZweck() : "";
        text = text.replace(";", " ").replace("\"", "'");
        
        // Find attachments
        String belegLink = "";
        List<BuchungDokument> attachments = docsByReferenz.get(Long.valueOf(b.getID()));
        if (attachments != null && !attachments.isEmpty())
        {
          for (BuchungDokument doc : attachments)
          {
            // Send messaging query to retrieve content
            QueryMessage qmMeta = new QueryMessage(doc.getUUID(), null);
            Application.getMessagingFactory().getMessagingQueue("jameica.messaging.getmeta").sendSyncMessage(qmMeta);
            Map<?, ?> map = (Map<?, ?>) qmMeta.getData();
            String origFilename = (map != null) ? (String) map.get("filename") : "beleg.pdf";
            String ext = origFilename.contains(".") ? origFilename.substring(origFilename.lastIndexOf('.')) : ".pdf";
            
            String archiveFilename = "Belege/Beleg_" + b.getID() + "_" + fileCounter + ext;
            fileCounter++;
            
            QueryMessage qmData = new QueryMessage(doc.getUUID(), null);
            Application.getMessagingFactory().getMessagingQueue("jameica.messaging.get").sendSyncMessage(qmData);
            byte[] fileBytes = (byte[]) qmData.getData();

            if (fileBytes != null)
            {
              // Write attachment inside the ZIP
              zos.putNextEntry(new ZipEntry(archiveFilename));
              zos.write(fileBytes);
              zos.closeEntry();
              belegLink = archiveFilename;
              
              // Add to XML index
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
      zos.write(bos.toByteArray());
      zos.closeEntry();

      // 2. Write EXTF_Kontenbeschriftungen_[Jahr].csv
      zos.putNextEntry(new ZipEntry("EXTF_Kontenbeschriftungen_" + targetYear + ".csv"));
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
          osw.write(String.format("%s;%s\n", b.getNummer(), b.getBezeichnung().replace(";", " ")));
        }
      }
      osw.flush();
      zos.write(bos.toByteArray());
      zos.closeEntry();

      // 3. Write document.xml
      xmlContent.append("  </content>\n");
      xmlContent.append("</archive>");
      zos.putNextEntry(new ZipEntry("document.xml"));
      zos.write(xmlContent.toString().getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }

    updateExportLogs("DATEV-Exportpaket erfolgreich erstellt und als ZIP gespeichert:\n" + zipFile.getAbsolutePath());
    GUI.getStatusBar().setSuccessText("DATEV-Exportpaket erfolgreich generiert!");
  }

  private void updateExportLogs(String msg)
  {
    if (exportLogsText != null && !exportLogsText.isDisposed())
    {
      String curr = exportLogsText.getText();
      exportLogsText.setText(curr + "\n" + msg);
    }
    Logger.info(msg);
  }
}
