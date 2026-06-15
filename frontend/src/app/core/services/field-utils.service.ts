import { Injectable } from '@angular/core';
import { PseudonymService } from '@app/core/services/pseudonym.service';

/**
 * Shared utility service for field type detection, multi-value handling,
 * label resolution, and URI parsing. Used by both CompanyCardView and
 * CreateEntityDialogComponent to eliminate code duplication.
 *
 * Field type detection relies on backend-provided `conceptSource` and
 * `isDatatypeProperty` — no hardcoded namespace strings.
 */
@Injectable({ providedIn: 'root' })
export class FieldUtilsService {

    constructor(private pseudo: PseudonymService) {}

    // ── Field type detection ──

    isSKOS(f: any): boolean {
        return f.conceptSource === 'skos';
    }

    isEsco(f: any): boolean {
        return f.conceptSource === 'esco';
    }

    isEscoOccupation(f: any): boolean {
        return this.isEsco(f) && (f.range || '').includes('Occupation');
    }

    isObjectProperty(f: any): boolean {
        return !f.isDatatypeProperty && f.range && f.conceptSource === 'none';
    }

    inputType(f: any): string {
        const range = f.range || '';
        if (range.includes('#date') || range.includes('#gYear')) return 'date';
        if (range.includes('#boolean')) return 'checkbox';
        if (range.includes('#int') || range.includes('#integer') || range.includes('#decimal')) return 'number';
        return 'text';
    }

    // ── URI helpers ──

    getPropName(uri: string): string {
        if (!uri) return '';
        const parts = uri.split('#');
        return parts.length > 1 ? parts.pop() || uri : uri.split('/').pop() || uri;
    }

    getLocalName(uri: string): string {
        if (!uri) return '';
        const parts = uri.split('/');
        return decodeURIComponent(parts[parts.length - 1] || uri);
    }

    // ── Multi-value helpers ──

    getMultiValue(formData: any, propName: string): string[] {
        const value = formData[propName];
        if (!value) return [];
        return Array.isArray(value) ? value : [value];
    }

    selectValue(formData: any, propName: string, uri: string, maxCount: number): void {
        if (maxCount === 1) {
            formData[propName] = uri;
        } else {
            const current = this.getMultiValue(formData, propName);
            if (!current.includes(uri)) {
                formData[propName] = [...current, uri];
            }
        }
    }

    removeValue(formData: any, propName: string, uri: string, maxCount: number): void {
        if (maxCount === 1) {
            formData[propName] = null;
        } else {
            const current = this.getMultiValue(formData, propName);
            formData[propName] = current.filter(v => v !== uri);
        }
    }

    // ── Label resolution ──

    getInstanceLabel(uri: string, instanceCaches: Record<string, any[]>): string {
        if (!uri) return '';
        for (const classUri of Object.keys(instanceCaches)) {
            const found = (instanceCaches[classUri] || []).find((inst: any) => inst.uri === uri);
            if (found?.label) return this.pseudo.label(uri, found.label);
        }
        return this.pseudo.label(uri, this.getLocalName(uri));
    }

    getConceptLabel(uri: string, propertyUri: string, conceptCaches: Record<string, any[]>): string {
        if (!uri) return '';
        const concepts = conceptCaches[propertyUri] || [];
        const found = concepts.find(c => c.uri === uri);
        if (found?.label) return found.label;
        return this.getLocalName(uri);
    }

    // ── Search filtering ──

    filterInstances(allInstances: any[], searchText: string, selectedUris: string[]): any[] {
        if (!searchText) return [];
        const lower = searchText.toLowerCase();
        return allInstances
            .filter(inst => !selectedUris.includes(inst.uri) &&
                (inst.label || '').toLowerCase().includes(lower))
            .slice(0, 20);
    }

    validateRequiredFields(fields: any[], formData: any): string[] {
        return fields
            .filter((f: any) => f.required)
            .filter((f: any) => {
                const val = formData[this.getPropName(f.property)];
                if (val == null) return true;
                if (typeof val === 'string') return val.trim() === '';
                if (Array.isArray(val)) return val.length === 0 || val.every((v: any) => typeof v === 'string' && v.trim() === '');
                return false;
            })
            .map((f: any) => f.label || f.property);
    }
}
