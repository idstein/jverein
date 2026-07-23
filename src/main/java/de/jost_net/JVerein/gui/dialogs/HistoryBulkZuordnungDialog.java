/**********************************************************************
 * Copyright (c) by Heiner Jostkleigrewe
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,  but WITHOUT ANY WARRANTY; without 
 *  even the implied warranty of  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See 
 *  the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.  If not, 
 * see <http://www.gnu.org/licenses/>.
 * 
 * heiner@jverein.de
 * www.jverein.de
 **********************************************************************/
package de.jost_net.JVerein.gui.dialogs;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.swt.SWT;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.Einstellungen.Property;
import de.jost_net.JVerein.rmi.Buchung;
import de.jost_net.JVerein.util.BuchungHistoryMatcher;
import de.jost_net.JVerein.util.BuchungHistoryMatcher.Proposal;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.gui.dialogs.AbstractDialog;
import de.willuhn.jameica.gui.input.DateInput;
import de.willuhn.jameica.gui.input.DecimalInput;
import de.willuhn.jameica.gui.parts.Button;
import de.willuhn.jameica.gui.parts.ButtonArea;
import de.willuhn.jameica.gui.util.SimpleContainer;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.BackgroundTask;
import de.willuhn.jameica.system.OperationCanceledException;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;

public class HistoryBulkZuordnungDialog extends AbstractDialog<Object>
{
  private static final int WINDOW_WIDTH = 550;

  private DateInput dateFrom = null;

  private DateInput dateUntil = null;

  private DecimalInput minScoreInput = null;

  public HistoryBulkZuordnungDialog(Date vondatum, Date bisdatum)
  {
    super(POSITION_CENTER);
    setTitle("Verlaufsklassifikation (Auto-Zuordnung)");
    setSize(WINDOW_WIDTH, SWT.DEFAULT);
    this.dateFrom = new DateInput(vondatum);
    this.dateUntil = new DateInput(bisdatum);
    double defScore = 0.5;
    try
    {
      defScore = (Double) Einstellungen.getEinstellung(Property.HISTORYMATCHINGMINSCORE);
    }
    catch (Exception e)
    {
      Logger.error("Could not load HISTORYMATCHINGMINSCORE setting", e);
    }
    this.minScoreInput = new DecimalInput(defScore, Einstellungen.DECIMALFORMAT);
  }

  @Override
  protected void paint(org.eclipse.swt.widgets.Composite parent) throws Exception
  {
    SimpleContainer container = new SimpleContainer(parent);
    container.addLabelPair("Datum von", dateFrom);
    container.addLabelPair("Datum bis", dateUntil);
    container.addLabelPair("Mindest-Score (0.0 - 1.0)", minScoreInput);

    ButtonArea buttons = new ButtonArea();
    buttons.addButton("Auto-Zuordnen", context -> {
      startBulkZuordnung();
      close();
    }, null, true, "ok.png");
    buttons.addButton("Abbrechen", context -> {
      throw new OperationCanceledException();
    }, null, false, "process-stop.png");
    container.addButtonArea(buttons);
  }

  @Override
  protected Object getData() throws Exception
  {
    return null;
  }

  private void startBulkZuordnung()
  {
    final Date von = (Date) dateFrom.getValue();
    final Date bis = (Date) dateUntil.getValue();
    Double scoreVal = (Double) minScoreInput.getValue();
    final double minScore = scoreVal != null ? scoreVal : 0.5;

    BackgroundTask t = new BackgroundTask()
    {
      private boolean interrupted = false;

      @Override
      public boolean isInterrupted()
      {
        return interrupted;
      }

      @Override
      public void run(ProgressMonitor monitor) throws ApplicationException
      {
        try
        {
          monitor.setStatusText("Suche unzugeordnete Buchungen...");
          DBIterator<Buchung> it = Einstellungen.getDBService().createList(Buchung.class);
          it.addFilter("buchungsart is null");
          if (von != null)
          {
            it.addFilter("datum >= ?", von);
          }
          if (bis != null)
          {
            it.addFilter("datum <= ?", bis);
          }

          List<Buchung> unassignedList = new ArrayList<>();
          while (it.hasNext())
          {
            unassignedList.add(it.next());
          }

          int total = unassignedList.size();
          int matchedCount = 0;

          for (int i = 0; i < total; i++)
          {
            if (interrupted)
            {
              break;
            }
            Buchung b = unassignedList.get(i);
            monitor.setPercentComplete((int) (((i + 1) / (double) total) * 100));
            monitor.setStatusText(String.format("Verarbeite Buchung %d/%d", i + 1, total));

            List<Proposal> proposals = BuchungHistoryMatcher.getProposals(
                b.getName(),
                b.getIban(),
                b.getZweck(),
                b.getBetrag()
            );

            if (!proposals.isEmpty())
            {
              Proposal best = proposals.get(0);
              if (best.getScore() >= minScore)
              {
                b.setBuchungsartId(best.getBuchungsartId());
                if (best.getBuchungsklasseId() != null)
                {
                  b.setBuchungsklasseId(best.getBuchungsklasseId());
                }
                if (best.getProjektId() != null)
                {
                  b.setProjektID(best.getProjektId());
                }
                b.store();
                matchedCount++;
              }
            }
          }

          final int resultCount = matchedCount;
          final int totalProcessed = total;
          GUI.getDisplay().asyncExec(() -> {
            GUI.getStatusBar().setSuccessText(
                String.format("Verlaufsklassifikation abgeschlossen: %d von %d Buchungen zugeordnet.", resultCount, totalProcessed)
            );
          });
        }
        catch (Exception e)
        {
          Logger.error("Fehler bei History Bulk Auto-Zuordnung", e);
          throw new ApplicationException("Fehler bei Verlaufsklassifikation: " + e.getMessage());
        }
      }

      @Override
      public void interrupt()
      {
        this.interrupted = true;
      }
    };

    Application.getController().start(t);
  }
}
