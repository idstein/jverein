/**********************************************************************
 * Copyright (c) 2026 by Heiner Jostkleigrewe and pair partners.
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 **********************************************************************/
package de.jost_net.JVerein.gui.control;

import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

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
import de.jost_net.JVerein.rmi.Buchung;
import de.jost_net.JVerein.rmi.Buchungsart;
import de.jost_net.JVerein.rmi.Buchungsklasse;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.gui.AbstractControl;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.Action;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.gui.input.SelectInput;
import de.willuhn.jameica.system.Settings;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;

public class UmsatzsteuerControl extends AbstractControl
{
  private Settings settings;
  private SelectInput targetYearInput;

  // UI Panels
  private Text statusText;
  private Table reverseTable;
  private Text elsterText;

  public UmsatzsteuerControl(AbstractView view)
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
    lbl.setText("Umsatzsteuer-Jahr wählen:");

    List<Integer> years = new ArrayList<>();
    int currentYear = Calendar.getInstance().get(Calendar.YEAR);
    for (int y = currentYear; y >= currentYear - 5; y--)
    {
      years.add(y);
    }

    int savedYear = settings.getInt("target_ust_year", currentYear - 1);
    targetYearInput = new SelectInput(years, savedYear);
    targetYearInput.addListener(evt -> {
      if (evt != null)
      {
        try
        {
          settings.setAttribute("target_ust_year", (Integer) targetYearInput.getValue());
          refreshCalculation();
        }
        catch (Exception e)
        {
          Logger.error("Fehler beim Aktualisieren der Umsatzsteuerjahre", e);
        }
      }
    });
    targetYearInput.paint(comp);

    return comp;
  }

  public void paintStatusTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    Group gr = new Group(parent, SWT.NONE);
    gr.setText("Prüfung Kleinunternehmerregelung (§ 19 UStG)");
    gr.setLayout(new GridLayout(1, false));
    gr.setLayoutData(new GridData(GridData.FILL_BOTH));

    statusText = new Text(gr, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
    statusText.setLayoutData(new GridData(GridData.FILL_BOTH));
    statusText.setText("Berechnungen werden geladen...");
  }

  public void paintReverseTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    Group tableGroup = new Group(parent, SWT.NONE);
    tableGroup.setText("Ermittelte § 13b Reverse-Charge Vorfälle (Auslandsdienstleistungen)");
    tableGroup.setLayout(new GridLayout(1, false));
    tableGroup.setLayoutData(new GridData(GridData.FILL_BOTH));

    reverseTable = new Table(tableGroup, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    reverseTable.setHeaderVisible(true);
    reverseTable.setLinesVisible(true);
    reverseTable.setLayoutData(new GridData(GridData.FILL_BOTH));

    String[] columns = {"Datum", "Lieferant (Name)", "Verwendungszweck", "Bemessungsgrundlage (Netto)", "Satz", "USt-Zahllast"};
    int[] widths = {80, 150, 180, 120, 60, 100};
    for (int i = 0; i < columns.length; i++)
    {
      TableColumn col = new TableColumn(reverseTable, SWT.LEFT);
      col.setText(columns[i]);
      col.setWidth(widths[i]);
    }
  }

  public void paintElsterTab(Composite parent) throws Exception
  {
    parent.setLayout(new GridLayout(1, false));

    Group gr = new Group(parent, SWT.NONE);
    gr.setText("Ausfüllhilfe für Ihre ELSTER Umsatzsteuererklärung");
    gr.setLayout(new GridLayout(1, false));
    gr.setLayoutData(new GridData(GridData.FILL_BOTH));

    elsterText = new Text(gr, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
    elsterText.setLayoutData(new GridData(GridData.FILL_BOTH));
    elsterText.setText("Ausfüllhilfe wird geladen...");

    // Trigger initial calculation load
    refreshCalculation();
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
          refreshCalculation();
          GUI.getStatusBar().setSuccessText("Berechnung erfolgreich aktualisiert.");
        }
        catch (Exception e)
        {
          Logger.error("Fehler beim Aktualisieren", e);
          throw new ApplicationException(e.getMessage());
        }
      }
    };
  }

  private void refreshCalculation() throws Exception
  {
    if (targetYearInput == null || targetYearInput.getValue() == null)
    {
      return;
    }
    int targetYear = (Integer) targetYearInput.getValue();
    int prevYear = targetYear - 1;

    Calendar cal = Calendar.getInstance();
    cal.set(prevYear, Calendar.JANUARY, 1, 0, 0, 0);
    Date fromDate = cal.getTime();
    cal.set(targetYear, Calendar.DECEMBER, 31, 23, 59, 59);
    Date toDate = cal.getTime();

    // Query bookings
    DBIterator<Buchung> it = Einstellungen.getDBService().createList(Buchung.class);
    it.addFilter("datum >= ?", fromDate);
    it.addFilter("datum <= ?", toDate);

    double revPrevYear = 0.0;
    double revTargetYear = 0.0;
    
    double reverseEuBase = 0.0;
    double reverseEuVat = 0.0;
    double reverseNonEuBase = 0.0;
    double reverseNonEuVat = 0.0;

    List<Buchung> reverseList = new ArrayList<>();

    while (it.hasNext())
    {
      Buchung b = it.next();
      cal.setTime(b.getDatum());
      int year = cal.get(Calendar.YEAR);

      Double betrag = b.getBetrag() != null ? b.getBetrag() : 0.0;
      Buchungsart bart = b.getBuchungsart();
      Buchungsklasse bklasse = bart != null ? bart.getBuchungsklasse() : null;
      
      // Map sphere
      KoerperschaftssteuerControl.Sphere sphere = KoerperschaftssteuerControl.getSphere(bklasse);

      // Compute total revenues for Kleinunternehmer check (exclude Ideeller Bereich membership fees and donations)
      if (betrag > 0 && sphere != KoerperschaftssteuerControl.Sphere.IDEELL)
      {
        if (year == prevYear)
        {
          revPrevYear += betrag;
        }
        else if (year == targetYear)
        {
          revTargetYear += betrag;
        }
      }

      // Check for § 13b Reverse Charge (expenses only in target year)
      if (year == targetYear && betrag < 0)
      {
        boolean isReverse = false;
        boolean isEu = true;

        if (b.getSteuer() != null && (b.getSteuer().getName().toLowerCase().contains("13b") 
            || b.getSteuer().getName().toLowerCase().contains("reverse")))
        {
          isReverse = true;
          // Check if EU or Non-EU (heuristic based on name)
          String name = b.getName() != null ? b.getName().toLowerCase() : "";
          if (name.contains("meta") || name.contains("facebook") || name.contains("us"))
          {
            isEu = false;
          }
        }
        else
        {
          String name = b.getName() != null ? b.getName().toLowerCase() : "";
          String zweck = b.getZweck() != null ? b.getZweck().toLowerCase() : "";
          String bartName = bart != null ? bart.getBezeichnung().toLowerCase() : "";
          
          if (zweck.contains("13b") || zweck.contains("reverse charge") 
              || bartName.contains("13b") || bartName.contains("reverse charge")
              || name.contains("google") || name.contains("zoom") 
              || name.contains("microsoft") || name.contains("meta") || name.contains("facebook"))
          {
            isReverse = true;
            if (name.contains("meta") || name.contains("facebook"))
            {
              isEu = false;
            }
          }
        }

        if (isReverse)
        {
          reverseList.add(b);
          double base = Math.abs(betrag);
          double rate = 0.19;
          if (b.getSteuer() != null && b.getSteuer().getSatz() != null)
          {
            rate = b.getSteuer().getSatz() / 100.0;
          }
          double vat = base * rate;

          if (isEu)
          {
            reverseEuBase += base;
            reverseEuVat += vat;
          }
          else
          {
            reverseNonEuBase += base;
            reverseNonEuVat += vat;
          }
        }
      }
    }

    // Populate Status Tab
    StringBuilder statusSb = new StringBuilder();
    statusSb.append("Steuerprüfung Kleinunternehmer-Status für das Jahr ").append(targetYear).append("\n");
    statusSb.append("=========================================================================\n\n");
    statusSb.append(String.format("Umsatz im Vorjahr (%d): %.2f € (Grenze: 22.000,00 €)\n", prevYear, revPrevYear));
    statusSb.append(String.format("Umsatz im laufenden Jahr (%d): %.2f € (Grenze: 50.000,00 €)\n\n", targetYear, revTargetYear));

    if (revPrevYear <= 22000.0 && revTargetYear <= 50000.0)
    {
      statusSb.append("[STATUS: OK] Der Verein erfüllt alle Kriterien für die Kleinunternehmerregelung (§ 19 UStG).\n");
      statusSb.append("Eigene Umsätze des Vereins müssen auf Rechnungen ohne Umsatzsteuer ausgewiesen werden.\n");
    }
    else
    {
      statusSb.append("[CRITISCH] Der Verein überschreitet die Kleinunternehmergrenzen!\n");
      statusSb.append("Es droht der Übergang zur Regelbesteuerung. Bitte Steuerberater kontaktieren.\n");
    }

    if (statusText != null && !statusText.isDisposed())
    {
      statusText.setText(statusSb.toString());
    }

    // Populate Table
    if (reverseTable != null && !reverseTable.isDisposed())
    {
      reverseTable.removeAll();
      SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
      for (Buchung b : reverseList)
      {
        double base = Math.abs(b.getBetrag());
        double rate = 0.19;
        if (b.getSteuer() != null && b.getSteuer().getSatz() != null)
        {
          r = b.getSteuer().getSatz() / 100.0;
        }
        else
        {
          r = 0.19;
        }
        double vat = base * r;

        TableItem item = new TableItem(reverseTable, SWT.NONE);
        item.setText(0, b.getDatum() != null ? sdf.format(b.getDatum()) : "");
        item.setText(1, b.getName() != null ? b.getName() : "");
        item.setText(2, b.getZweck() != null ? b.getZweck() : "");
        item.setText(3, String.format("%.2f €", base));
        item.setText(4, String.format("%.0f%%", r * 100.0));
        item.setText(5, String.format("%.2f €", vat));
      }
    }

    // Populate ELSTER text
    StringBuilder elsterSb = new StringBuilder();
    elsterSb.append("Kennzahlen für die ELSTER Umsatzsteuererklärung ").append(targetYear).append("\n");
    elsterSb.append("=========================================================================\n\n");
    elsterSb.append("Geben Sie diese Zahlen direkt in die entsprechenden Felder ein:\n\n");
    elsterSb.append("1. Angaben zur Besteuerung der Kleinunternehmer (§ 19 Abs. 1 UStG):\n");
    elsterSb.append(String.format("   - Zeile 33 (Umsatz im Vorjahr %d): %.0f €\n", prevYear, Math.round(revPrevYear)));
    elsterSb.append(String.format("   - Zeile 34 (Umsatz im laufenden Jahr %d): %.0f €\n\n", targetYear, Math.round(revTargetYear)));
    
    elsterSb.append("2. Leistungsempfänger als Steuerschuldner (§ 13b UStG) - Reverse Charge:\n");
    elsterSb.append("   a) Für Leistungen von Unternehmen aus dem EU-Ausland (z.B. Google Ireland, Zoom, Microsoft Ireland):\n");
    elsterSb.append("      - Zeile 94 (Bemessungsgrundlage): ").append(String.format("%.0f €", Math.round(reverseEuBase))).append("\n");
    elsterSb.append("      - Zeile 94 (Steuerbetrag 19%): ").append(String.format("%.2f €", reverseEuVat)).append("\n\n");

    elsterSb.append("   b) Für Leistungen von Unternehmen aus dem Drittland / Nicht-EU (z.B. Facebook/Meta USA):\n");
    elsterSb.append("      - Zeile 95 (Bemessungsgrundlage): ").append(String.format("%.0f €", Math.round(reverseNonEuBase))).append("\n");
    elsterSb.append("      - Zeile 95 (Steuerbetrag 19%): ").append(String.format("%.2f €", reverseNonEuVat)).append("\n\n");

    elsterSb.append("Gesamte abzuführende Umsatzsteuerschuld (Zahllast): ").append(String.format("%.2f €", reverseEuVat + reverseNonEuVat)).append("\n");

    if (elsterText != null && !elsterText.isDisposed())
    {
      elsterText.setText(elsterSb.toString());
    }
  }
  
  private double r; // Helper variable for lambda scope compiling
}
