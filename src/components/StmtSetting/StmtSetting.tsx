import { useState } from 'react';
import { useSelection } from 'reaflow';

export const StmtSettingSideBar = ({ className, currentSelection }) => {
    return (
        <div className={className}>
            <p>{currentSelection}</p>
        </div>);
    }